"""Shared provider lifecycle infrastructure."""

from .browser import BrowserResult, run_browser_flow
from .mail import (
    BaseMailClient,
    Mailbox,
    MailTmClient,
    TempMailClient,
    TempMailIoClient,
    create_mail_client,
)
from .process_reaper import BrowserProcessReaper, process_reaper

__all__ = [
    "BaseMailClient",
    "BrowserProcessReaper",
    "BrowserResult",
    "MailTmClient",
    "Mailbox",
    "TempMailClient",
    "TempMailIoClient",
    "create_mail_client",
    "process_reaper",
    "run_browser_flow",
]
