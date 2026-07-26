FROM python:3.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    MOZ_DISABLE_CONTENT_SANDBOX=1 \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    XDG_CACHE_HOME=/opt/any2api-cache

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl fonts-liberation fonts-noto-color-emoji \
        libasound2 libatk-bridge2.0-0 libatk1.0-0 libcups2 libdbus-1-3 \
        libdrm2 libgbm1 libgtk-3-0 libnspr4 libnss3 libpango-1.0-0 \
        libx11-6 libx11-xcb1 libxcb1 libxcomposite1 libxdamage1 libxext6 \
        libxfixes3 libxkbcommon0 libxrandr2 libxshmfence1 libxss1 libxtst6 \
        tzdata xvfb \
    && rm -rf /var/lib/apt/lists/*

COPY --from=ghcr.io/astral-sh/uv:0.9.5 /uv /uvx /bin/
COPY pyproject.toml uv.lock ./
COPY any2api_automation ./any2api_automation
RUN uv sync --frozen --no-dev \
    && uv run patchright install chromium \
    && uv run python -m camoufox fetch

RUN useradd --system --uid 10001 --create-home automation \
    && chown -R automation:automation /app /ms-playwright /opt/any2api-cache
USER 10001

EXPOSE 8090
CMD ["uv", "run", "uvicorn", "any2api_automation.main:app", "--host", "0.0.0.0", "--port", "8090"]
