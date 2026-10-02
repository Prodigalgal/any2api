"""Check source version contracts and optionally the deployable backend JAR."""

import argparse
import ast
import json
import re
import tomllib
import zipfile
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    backend = (root / "backend/build.gradle.kts").read_text(encoding="utf-8")
    version = re.search(r'^version\s*=\s*"(\d+\.\d+\.\d+)"', backend, re.MULTILINE).group(1)
    package = json.loads((root / "web/package.json").read_text(encoding="utf-8"))
    lock = json.loads((root / "web/package-lock.json").read_text(encoding="utf-8"))
    automation = tomllib.loads((root / "automation/pyproject.toml").read_text(encoding="utf-8"))
    uv = tomllib.loads((root / "automation/uv.lock").read_text(encoding="utf-8"))
    uv_project = next(
        package for package in uv["package"] if package["name"] == "any2api-automation"
    )
    tree = ast.parse((root / "automation/any2api_automation/main.py").read_text(encoding="utf-8"))
    fastapi = next(
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Name)
        and node.func.id == "FastAPI"
    )
    app_version = next(
        ast.literal_eval(keyword.value) for keyword in fastapi.keywords if keyword.arg == "version"
    )
    migration = (
        root / "backend/src/main/resources/db/changelog/db.changelog-master.yaml"
    ).read_text(encoding="utf-8")
    tags = re.findall(r"^\s+tag:\s*(\d+\.\d+\.\d+)\s*$", migration, re.MULTILINE)
    values = {
        "backend": version,
        "web": package["version"],
        "web_lock": lock["version"],
        "web_lock_root": lock["packages"][""]["version"],
        "automation": automation["project"]["version"],
        "automation_lock": uv_project["version"],
        "automation_app": app_version,
    }
    latest_tag = tags[-1]
    if tuple(map(int, latest_tag.split("."))) > tuple(map(int, version.split("."))):
        raise SystemExit(f"Migration tag {latest_tag} is newer than source version {version}")
    if args.jar:
        with zipfile.ZipFile(args.jar) as jar:
            manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
        values["backend_jar"] = re.search(
            r"^Implementation-Version:\s*(\S+)", manifest, re.MULTILINE
        ).group(1)
    mismatches = {name: value for name, value in values.items() if value != version}
    if mismatches:
        raise SystemExit(f"Version contract failed: expected {version}, found {mismatches}")
    print(
        json.dumps(
            {
                "status": "PASS",
                "version": version,
                "latest_migration_tag": latest_tag,
                "components": values,
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
