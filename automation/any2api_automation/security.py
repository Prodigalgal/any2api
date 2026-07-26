import hmac

from fastapi import Header, HTTPException, status

from .config import settings


async def require_internal_token(authorization: str | None = Header(default=None)) -> None:
    expected = settings().internal_token
    if not expected:
        return
    actual = authorization[7:] if authorization and authorization.startswith("Bearer ") else ""
    if not hmac.compare_digest(expected, actual):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid service token"
        )
