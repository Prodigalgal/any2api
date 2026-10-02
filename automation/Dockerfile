ARG BROWSER_RUNTIME_IMAGE=docker.io/speedproxy/any2api:browser-runtime-sha-0a4a043a696320b3ad990629d1f871f04311e233@sha256:22954d16b8bd1b06619289825b320b751f6d11335c6ec9665af0453982a128c2
FROM ${BROWSER_RUNTIME_IMAGE}

WORKDIR /app

COPY --chown=10001:10001 any2api_automation ./any2api_automation
COPY --chown=10001:10001 pyproject.toml check_runtime_version.py ./

RUN uv pip install --python /app/.venv/bin/python --no-deps --reinstall-package any2api-automation . \
    && /app/.venv/bin/python check_runtime_version.py

EXPOSE 8090
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["/bin/sh", "-c", "Xvfb :99 -screen 0 1365x900x24 -nolisten tcp -ac & for i in $(seq 1 50); do [ -S /tmp/.X11-unix/X99 ] && break; sleep 0.1; done; [ -S /tmp/.X11-unix/X99 ] || exit 1; export DISPLAY=:99; exec /app/.venv/bin/uvicorn any2api_automation.main:app --host 0.0.0.0 --port 8090"]
