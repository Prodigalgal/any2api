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

ARG TARGETARCH
ARG SING_BOX_VERSION=1.13.14

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl fonts-liberation fonts-noto-color-emoji \
        libasound2 libatk-bridge2.0-0 libatk1.0-0 libcups2 libdbus-1-3 \
        libdrm2 libgbm1 libgtk-3-0 libnspr4 libnss3 libpango-1.0-0 \
        libx11-6 libx11-xcb1 libxcb1 libxcomposite1 libxdamage1 libxext6 \
        libxfixes3 libxkbcommon0 libxrandr2 libxshmfence1 libxss1 libxtst6 \
        tini tzdata xvfb \
    && test "$TARGETARCH" = "arm64" \
    && curl -fsSL "https://github.com/SagerNet/sing-box/releases/download/v${SING_BOX_VERSION}/sing-box-${SING_BOX_VERSION}-linux-arm64.tar.gz" \
        | tar -xz -C /tmp \
    && install -m 0755 "/tmp/sing-box-${SING_BOX_VERSION}-linux-arm64/sing-box" /usr/local/bin/sing-box \
    && rm -rf "/tmp/sing-box-${SING_BOX_VERSION}-linux-arm64" \
    && rm -rf /var/lib/apt/lists/*

COPY --from=ghcr.io/astral-sh/uv:0.9.5 /uv /uvx /bin/
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project \
    && uv run patchright install chromium \
    && uv run python -m camoufox fetch

COPY any2api_automation ./any2api_automation
RUN uv sync --frozen --no-dev

RUN useradd --system --uid 10001 --create-home automation \
    && chown -R automation:automation /app /ms-playwright /opt/any2api-cache
USER 10001

EXPOSE 8090
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["/bin/sh", "-c", "Xvfb :99 -screen 0 1365x900x24 -nolisten tcp -ac & for i in $(seq 1 50); do [ -S /tmp/.X11-unix/X99 ] && break; sleep 0.1; done; [ -S /tmp/.X11-unix/X99 ] || exit 1; export DISPLAY=:99; exec uv run uvicorn any2api_automation.main:app --host 0.0.0.0 --port 8090"]
