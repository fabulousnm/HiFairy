import asyncio
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

import httpx
from fastapi import FastAPI

sys.path.insert(0, str(Path(__file__).parent))
import agent_gateway as gateway


def test_agent_gateway():
    temp = tempfile.TemporaryDirectory()
    mobile = Path(temp.name) / "mobile.token"
    bridge = Path(temp.name) / "bridge.token"
    mobile.write_text("mobile-secret", encoding="ascii")
    bridge.write_text("bridge-secret", encoding="ascii")
    calls = []

    def upstream(request):
        calls.append((request.method, request.url.path, request.headers.get("authorization")))
        return httpx.Response(200, json={"items": []})

    original = httpx.AsyncClient
    transport = httpx.MockTransport(upstream)

    def client_factory(*args, **kwargs):
        kwargs["transport"] = transport
        return original(*args, **kwargs)

    app = FastAPI()
    app.include_router(gateway.router)

    async def check():
        async with original(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            assert (await client.get("/agent/sessions")).status_code == 401
            assert (await client.get("/agent/other", headers={"Authorization": "Bearer mobile-secret"})).status_code == 404
            assert (await client.get("/agent/sessions", headers={"Authorization": "Bearer mobile-secret"})).status_code == 200
            assert (await client.get("/agent/sessions/session-abc/recent", headers={"Authorization": "Bearer mobile-secret"})).status_code == 200
            assert (await client.get("/agent/sessions/session-abc/details/tool/call_01_x", headers={"Authorization": "Bearer mobile-secret"})).status_code == 200
            assert (await client.get("/agent/sessions/session-abc/details/terminal/12", headers={"Authorization": "Bearer mobile-secret"})).status_code == 200
            assert (await client.get("/agent/sessions/session-abc/details/commentary/13", headers={"Authorization": "Bearer mobile-secret"})).status_code == 200
            assert (await client.get("/agent/sessions/session-abc/details/tool/..", headers={"Authorization": "Bearer mobile-secret"})).status_code == 404
            assert calls == [("GET", "/sessions", "Bearer bridge-secret"),
                             ("GET", "/sessions/session-abc/recent", "Bearer bridge-secret"),
                             ("GET", "/sessions/session-abc/details/tool/call_01_x", "Bearer bridge-secret"),
                             ("GET", "/sessions/session-abc/details/terminal/12", "Bearer bridge-secret"),
                             ("GET", "/sessions/session-abc/details/commentary/13", "Bearer bridge-secret")]

    with patch.object(gateway, "MOBILE_TOKEN_FILE", mobile), patch.object(gateway, "BRIDGE_TOKEN_FILE", bridge), patch.object(gateway.httpx, "AsyncClient", client_factory):
        asyncio.run(check())
    temp.cleanup()


if __name__ == "__main__":
    test_agent_gateway()
