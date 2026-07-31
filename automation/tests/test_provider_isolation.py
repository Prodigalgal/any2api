import re
from pathlib import Path

from any2api_automation.providers import provider_registry


def test_provider_ids_do_not_leak_into_automation_core() -> None:
    package_root = Path(__file__).parents[1] / "any2api_automation"
    provider_root = package_root / "providers"
    provider_ids = [manifest["id"] for manifest in provider_registry.public_manifests()]

    for source in package_root.rglob("*.py"):
        if source.is_relative_to(provider_root):
            continue
        content = source.read_text(encoding="utf-8")
        for provider_id in provider_ids:
            assert re.search(rf"\b{re.escape(str(provider_id))}\b", content) is None, (
                f"provider id {provider_id} leaked into automation core: {source}"
            )


def test_provider_plugins_do_not_reference_sibling_plugins() -> None:
    provider_root = Path(__file__).parents[1] / "any2api_automation" / "providers"
    provider_ids = sorted(
        (str(manifest["id"]) for manifest in provider_registry.public_manifests()),
        key=len,
        reverse=True,
    )

    for source in provider_root.rglob("*.py"):
        relative = source.relative_to(provider_root)
        component = Path(relative.parts[0]).stem
        owner = next(
            (
                provider_id
                for provider_id in provider_ids
                if component == provider_id or component.startswith(f"{provider_id}_")
            ),
            None,
        )
        content = source.read_text(encoding="utf-8")
        for provider_id in provider_ids:
            if provider_id == owner:
                continue
            sibling_import = re.compile(
                rf"(?:from|import)\s+(?:any2api_automation\.providers\.)?"
                rf"{re.escape(provider_id)}(?:\s|\.|$)"
                rf"|from\s+\.{1, 2}{re.escape(provider_id)}(?:\s|\.|$)",
                re.MULTILINE,
            )
            assert sibling_import.search(content) is None, (
                f"provider {owner or 'shared'} references sibling {provider_id}: {source}"
            )
