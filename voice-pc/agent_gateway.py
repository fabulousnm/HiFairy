"""Authenticated Agent routes on the existing Fairy voice origin."""

import hmac
import asyncio
from pathlib import Path

import httpx
from fastapi import APIRouter, Header, HTTPException, Request, WebSocket
from fastapi.responses import Response, StreamingResponse

router = APIRouter(prefix="/agent")
BRIDGE_URL = "http://127.0.0.1:18765"
MOBILE_TOKEN_FILE = Path(__file__).parent / "vendor" / "agent-mobile-token.txt"
BRIDGE_TOKEN_FILE = Path("D:/dsh-data/hifairy-agent-probe.token")


def _tokens(authorization: str | None) -> str:
    try:
        mobile = MOBILE_TOKEN_FILE.read_text(encoding="ascii").strip()
        bridge = BRIDGE_TOKEN_FILE.read_text(encoding="ascii").strip()
    except OSError:
        raise HTTPException(503, "agent_unconfigured") from None
    candidate = (authorization or "").removeprefix("Bearer ")
    if not mobile or not hmac.compare_digest(candidate, mobile):
        raise HTTPException(401, "unauthorized")
    return bridge


def _bridge_path(path: str) -> str:
    # Never allow callers to choose another local address or arbitrary path.
    if path in {"sessions", "modes", "workspaces"}:
        return "/" + path
    if path in {"filesystem/roots", "filesystem/files", "filesystem/upload", "filesystem/download"}:
        return "/" + path
    parts = path.split("/")
    if len(parts) == 3 and parts[0] == "workspaces" and parts[2] in {"files", "upload", "download"} and parts[1] and all(c.isascii() and (c.isalnum() or c in "-_") for c in parts[1]):
        return "/" + path
    valid_session = len(parts) >= 3 and parts[0] == "sessions" and parts[1].startswith("session-")
    valid_suffix = len(parts) == 3 and parts[2] in {"messages", "snapshot", "events", "recent", "cancel", "attachments"}
    valid_detail = len(parts) == 5 and parts[2] == "details" and parts[3] in {"reasoning", "tool", "terminal", "commentary"} and parts[4] and all(c.isascii() and (c.isalnum() or c in "-_") for c in parts[4])
    if valid_session and (valid_suffix or valid_detail):
        suffix = parts[1][8:]
        if suffix and all(c.islower() or c.isdigit() or c == "-" for c in suffix):
            return "/" + path
    raise HTTPException(404, "not_found")


@router.websocket("/live/{session_id}")
async def agent_live(ws: WebSocket, session_id: str):
    await ws.accept()
    try:
        target = _bridge_path(f"sessions/{session_id}/events")
        hello = await asyncio.wait_for(ws.receive_json(), timeout=10)
        bridge_token = _tokens(f"Bearer {hello.get('token', '')}")
    except (HTTPException, asyncio.TimeoutError, ValueError, AttributeError):
        await ws.close(code=1008)
        return
    try:
        async with httpx.AsyncClient(timeout=None, trust_env=False) as client:
            async with client.stream("GET", BRIDGE_URL + target,
                                     headers={"Authorization": f"Bearer {bridge_token}"}) as upstream:
                if upstream.status_code != 200:
                    await ws.send_json({"type": "error", "error": "bridge_unavailable"})
                    return
                async for line in upstream.aiter_lines():
                    if line.startswith("data: "):
                        await ws.send_text(line[6:])
    except (httpx.HTTPError, RuntimeError):
        try:
            await ws.send_json({"type": "error", "error": "bridge_unavailable"})
        except RuntimeError:
            pass
    finally:
        try:
            await ws.close()
        except RuntimeError:
            pass


@router.api_route("/{path:path}", methods=["GET", "POST"])
async def agent_proxy(path: str, request: Request, authorization: str | None = Header(default=None)):
    bridge_token = _tokens(authorization)
    target = _bridge_path(path)
    allowed = (request.method == "GET" and not target.endswith(("/messages", "/upload", "/cancel", "/attachments"))) or (request.method == "POST" and (target == "/sessions" or target.endswith(("/messages", "/upload", "/cancel", "/attachments"))))
    if not allowed:
        raise HTTPException(405, "method_not_allowed")
    headers = {"Authorization": f"Bearer {bridge_token}"}
    url = BRIDGE_URL + target
    if target.endswith(("/files", "/download", "/upload", "/attachments")):
        query = request.url.query
        if len(query) > 2048:
            raise HTTPException(400, "query_too_large")
        url += "?" + query if query else ""
    if target.endswith(("/upload", "/attachments")):
        if int(request.headers.get("content-length", "0")) > 25 * 1024 * 1024:
            raise HTTPException(413, "request_too_large")
        try:
            async with httpx.AsyncClient(timeout=60, trust_env=False) as client:
                upstream = await client.post(url, headers=headers, content=request.stream())
        except httpx.HTTPError:
            raise HTTPException(503, "bridge_unavailable") from None
        return Response(upstream.content, status_code=upstream.status_code, media_type="application/json", headers={"Cache-Control": "no-store"})
    if target.endswith("/download"):
        async with httpx.AsyncClient(timeout=30, trust_env=False) as client:
            upstream = await client.get(url, headers=headers)
        return Response(upstream.content, status_code=upstream.status_code,
                        media_type="application/octet-stream" if upstream.status_code == 200 else "application/json",
                        headers={"Cache-Control": "no-store"})
    if target.endswith("/events"):
        async def events():
            try:
                async with httpx.AsyncClient(timeout=None, trust_env=False) as client:
                    async with client.stream("GET", url, headers=headers) as upstream:
                        if upstream.status_code != 200:
                            yield b'event: error\ndata: {"error":"bridge_unavailable"}\n\n'
                            return
                        async for chunk in upstream.aiter_bytes():
                            yield chunk
            except httpx.HTTPError:
                yield b'event: error\ndata: {"error":"bridge_unavailable"}\n\n'
        return StreamingResponse(events(), media_type="text/event-stream", headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"})
    body = await request.body() if request.method == "POST" else None
    if body is not None and len(body) > 16384:
        raise HTTPException(413, "request_too_large")
    try:
        async with httpx.AsyncClient(timeout=15, trust_env=False) as client:
            upstream = await client.request(request.method, url, headers=headers, content=body)
    except httpx.HTTPError:
        raise HTTPException(503, "bridge_unavailable") from None
    return Response(upstream.content, status_code=upstream.status_code, media_type="application/json", headers={"Cache-Control": "no-store"})
