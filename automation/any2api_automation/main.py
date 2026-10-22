import logging
import time
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Response

from .blocking_work import worker_snapshot, workers_healthy
from .browser_budget import browser_process_budget
from .browser_transport import manager as browser_session_manager
from .browser_transport import router as browser_transport_router
from .captcha.api import router as captcha_router
from .captcha.registry import registry
from .config import settings
from .lifecycle.process_reaper import process_reaper
from .lifecycle.proxy import proxy_tunnel_pool
from .observability import CorrelationLoggingMiddleware
from .provider_api import router as provider_router
from .providers import provider_registry, public_provider_manifests
from .resources import lanes
from .security import require_internal_token

application_logger = logging.getLogger("any2api_automation")
application_logger.setLevel(settings().log_level)
uvicorn_handlers = logging.getLogger("uvicorn").handlers
if uvicorn_handlers:
    application_logger.handlers = list(uvicorn_handlers)
elif not application_logger.handlers:
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter("%(levelname)s: %(name)s %(message)s"))
    application_logger.addHandler(handler)
application_logger.propagate = False


@asynccontextmanager
async def lifespan(_: FastAPI):
    application_logger.info("automation_observability_ready")
    async with proxy_tunnel_pool.lifecycle():
        await process_reaper.start()
        try:
            yield
        finally:
            await process_reaper.stop()
            await provider_registry.close()
            browser_session_manager.close_all()


app = FastAPI(
    title="Any2API Automation",
    version="1.6.7",
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


def _background_healthy(multiplier: int) -> bool:
    config = settings()
    grace = max(120, config.browser_reaper_interval_seconds * multiplier)
    last_sweep = process_reaper.stats.last_sweep_at
    reaper_ready = (
        not config.browser_reaper_enabled or not last_sweep or time.time() - last_sweep < grace
    )
    return reaper_ready and workers_healthy(grace)


@app.get("/health/live")
async def live(response: Response) -> dict[str, object]:
    healthy = _background_healthy(8)
    response.status_code = 200 if healthy else 503
    return {"ok": healthy, "service": settings().service_name}


@app.get("/health/ready")
async def ready(response: Response) -> dict[str, object]:
    capabilities = registry.capabilities()
    local_solver_ready = any(
        item["available"] for item in capabilities.values()
    ) and _background_healthy(4)
    response.status_code = 200 if local_solver_ready else 503
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
        "resources": {
            **lanes.snapshot.__dict__,
            "browser_process": browser_process_budget.snapshot().__dict__,
            "process_reaper": process_reaper.snapshot(),
            "proxy_tunnels": proxy_tunnel_pool.snapshot(),
            "workers": worker_snapshot(),
        },
    }
