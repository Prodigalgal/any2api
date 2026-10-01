import inspect
from importlib import import_module
from pkgutil import iter_modules

from ..config import settings
from .base import AutomationProvider
from .registry import AutomationProviderRegistry


def _discover() -> list[AutomationProvider]:
    raw_filter = settings().enabled_providers.strip()
    enabled = {x.strip() for x in raw_filter.split(",") if x.strip()} if raw_filter else None
    discovered: list[AutomationProvider] = []
    for module_info in iter_modules(__path__):
        if module_info.name in {"base", "registry"}:
            continue
        module = import_module(f"{__name__}.{module_info.name}")
        for _, candidate in inspect.getmembers(module, inspect.isclass):
            if (
                candidate is not AutomationProvider
                and issubclass(candidate, AutomationProvider)
                and candidate.__module__ == module.__name__
            ):
                instance = candidate()
                if enabled is not None and instance.manifest.id not in enabled:
                    continue
                discovered.append(instance)
    return discovered


provider_registry = AutomationProviderRegistry(_discover())


def public_provider_manifests() -> list[dict[str, object]]:
    return provider_registry.public_manifests()
