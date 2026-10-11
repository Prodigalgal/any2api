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

__all__ = [
    "BaseMailClient",
    "BrowserResult",
    "MailTmClient",
    "Mailbox",
    "TempMailClient",
    "TempMailIoClient",
    "create_mail_client",
    "run_browser_flow",
]
