"""Shared provider lifecycle infrastructure."""

from .browser import BrowserResult, run_browser_flow
from .mail import Mailbox, TempMailClient

__all__ = ["BrowserResult", "Mailbox", "TempMailClient", "run_browser_flow"]
