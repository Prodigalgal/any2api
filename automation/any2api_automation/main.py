import logging
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI

from .browser_transport import manager as browser_session_manager
from .browser_transport import router as browser_transport_router
from .captcha.api import router as captcha_router
from .captcha.registry import registry
from .config import settings
from .observability import CorrelationLoggingMiddleware
from .provider_api import router as provider_router
from .providers import provider_registry, public_provider_manifests
from .resources import lanes
from .security import require_internal_token

logging.getLogger("any2api_automation").setLevel(settings().log_level)


@asynccontextmanager
async def lifespan(_: FastAPI):
    try:
        yield
    finally:
        browser_session_manager.close_all()


app = FastAPI(
    title="Any2API Automation",
    version="0.1.0",
    docs_url=None,
    redoc_url=None,
    lifespan=lifespan,
)
app.add_middleware(CorrelationLoggingMiddleware)
app.include_router(captcha_router)
app.include_router(provider_router)
app.include_router(browser_transport_router)
for provider_router_extension in provider_registry.routers():
    app.include_router(provider_router_extension)


@app.get("/health/live")
async def live() -> dict[str, object]:
    return {"ok": True, "service": settings().service_name}


@app.get("/health/ready")
async def ready() -> dict[str, object]:
    capabilities = registry.capabilities()
    local_solver_ready = any(item["available"] for item in capabilities.values())
    return {
        "ok": local_solver_ready,
        "service": settings().service_name,
        "solvers": capabilities,
    }


@app.get("/internal/v1/capabilities", dependencies=[Depends(require_internal_token)])
async def platform_capabilities() -> dict[str, object]:
    return {
        "ok": True,
        "providers": public_provider_manifests(),
        "solvers": registry.capabilities(),
        "resources": lanes.snapshot.__dict__,
    }
