from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from ..browser_transport import manager as browser_session_manager
from ..lifecycle.browser import BrowserLaunchProfile, launch_browser
from ..security import require_internal_token
from .glm_challenge import GlmAliyunChallenge
from .glm_settings import settings


class GlmCaptchaRequest(BaseModel):
    timeout_seconds: int = Field(default=120, ge=30, le=240)


router = APIRouter(
    prefix="/internal/v1/providers/glm/browser-sessions",
    dependencies=[Depends(require_internal_token)],
)


@router.post("/{session_id}/captcha")
def solve_chat_captcha(session_id: str, request: GlmCaptchaRequest) -> dict[str, object]:
    try:
        with browser_session_manager.lease(session_id) as entry:
            challenge_settings = settings()
            with launch_browser(
                "camoufox",
                "patchright",
                headless=True,
                proxy_url=entry.proxy_url,
                profile=BrowserLaunchProfile(
                    humanize=True,
                    camoufox_os="windows",
                ),
            ) as (backend, browser):
                context = browser.new_context(
                    locale="en-US",
                    timezone_id="Asia/Tokyo",
                    viewport={"width": 1440, "height": 900},
                )
                page = context.new_page()
                try:
                    page.goto(
                        challenge_settings.glm_base_url,
                        wait_until="domcontentloaded",
                        timeout=90_000,
                    )
                    page.wait_for_timeout(2_000)
                    challenge = GlmAliyunChallenge.for_chat()
                    ticket = challenge.solve(page, timeout_seconds=request.timeout_seconds)
                    return {
                        "ok": True,
                        "ticket": ticket,
                        "backend": backend,
                        "diagnostic": challenge.last_diagnostic,
                    }
                finally:
                    context.close()
    except KeyError as error:
        raise HTTPException(status_code=404, detail="browser session does not exist") from error
