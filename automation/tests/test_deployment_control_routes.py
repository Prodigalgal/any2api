"""Guard long-running account verification and GitOps patch propagation."""

import json
import re
import textwrap
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
PROBE_PATCH = ROOT / "deploy/gitops/admin-account-probe-timeouts.json"


@pytest.mark.parametrize(
    ("path", "expected"),
    [
        ("/api/admin/v1/accounts/0bb13730-997e-4488-83e3-8df724d29752/probe", True),
        ("/api/admin/v1/accounts/0BB13730-997E-4488-83E3-8DF724D29752/probe", True),
        ("/api/admin/v1/accounts/not-an-account/probe", False),
        ("/api/admin/v1/accounts/0bb13730-997e-4488-83e3-8df724d29752", False),
        ("/api/admin/v1/accounts/0bb13730-997e-4488-83e3-8df724d29752/commands/probe", False),
        ("/api/admin/v1/accounts/0bb13730-997e-4488-83e3-8df724d29752/probe/extra", False),
        ("/api/admin/v1/accounts/page", False),
    ],
)
def test_legacy_probe_timeout_does_not_expand_to_other_admin_paths(path, expected):
    patch = json.loads(PROBE_PATCH.read_text(encoding="utf8"))
    match = next(operation["value"] for operation in patch if operation["op"] == "add")
    assert match["method"] == "POST"
    assert bool(re.fullmatch(match["path"]["value"], path)) is expected


def test_release_update_copies_patches_with_target_and_preserves_existing_patches(
    tmp_path, monkeypatch
):
    workflow = (ROOT / ".github/workflows/build-and-deploy.yml").read_text(encoding="utf8")
    step = workflow.split("- name: Update immutable image tags, namespace and server placement", 1)[
        1
    ]
    script = textwrap.dedent(step.split("python3 - <<'PY'\n", 1)[1].split("\n          PY", 1)[0])
    oracle = tmp_path / "any2api/oracle"
    oracle.mkdir(parents=True)
    images = "".join(
        f"  - name: any2api-{component}\n"
        "    newName: docker.io/speedproxy/any2api\n"
        f"    newTag: {component}-previous\n"
        for component in ("server", "automation", "automation-arena", "automation-deepseek", "web")
    )
    manifest = oracle / "kustomization.yaml"
    manifest.write_text(
        "apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nnamespace: any2api\n"
        "resources:\n  - ../base\nimages:\n"
        + images
        + "patches:\n  - path: existing-site-patch.yaml\n",
        encoding="utf8",
    )
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("GITHUB_WORKSPACE", str(ROOT))
    monkeypatch.setenv("IMAGE_SUFFIX", "20261019-v1.5.13-contract-check")
    exec(compile(script, "gitops-release-script", "exec"), {})  # noqa: S102 - checked-in CI code
    first = manifest.read_text(encoding="utf8")
    exec(compile(script, "gitops-release-script", "exec"), {})  # noqa: S102 - idempotency check
    assert manifest.read_text(encoding="utf8") == first
    assert "  - path: existing-site-patch.yaml\n" in first
    assert re.search(
        r"path: admin-account-probe-timeouts\.json\n\s+target:\n"
        r"\s+group: gateway.networking.k8s.io\n\s+version: v1\n"
        r"\s+kind: HTTPRoute\n\s+name: any2api-control",
        first,
    )
    for name in (
        "server-database-affinity.yaml",
        "automation-runtime-resources.yaml",
        "namespace-runtime-quota.yaml",
        "admin-account-probe-timeouts.json",
    ):
        assert (oracle / name).read_bytes() == (ROOT / "deploy/gitops" / name).read_bytes()
