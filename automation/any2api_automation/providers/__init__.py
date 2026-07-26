import inspect
from importlib import import_module
from pkgutil import iter_modules

from .base import AutomationProvider
from .registry import AutomationProviderRegistry


def _discover() -> list[AutomationProvider]:
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
                discovered.append(candidate())
    return discovered


provider_registry = AutomationProviderRegistry(_discover())


def public_provider_manifests() -> list[dict[str, object]]:
    return provider_registry.public_manifests()
