"""Authenticated Agent routes on the existing Fairy voice origin."""

import hmac
from pathlib import Path

import httpx
from fastapi import APIRouter, Header, HTTPException, Request
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
    if path == "sessions":
        return "/sessions"
    parts = path.split("/")
    if len(parts) == 3 and parts[0] == "sessions" and parts[1].startswith("session-") and parts[2] in {"messages", "snapshot", "events"}:
        suffix = parts[1][8:]
        if suffix and all(c.islower() or c.isdigit() or c == "-" for c in suffix):
            return "/" + path
    raise HTTPException(404, "not_found")


@router.api_route("/{path:path}", methods=["GET", "POST"])
async def agent_proxy(path: str, request: Request, authorization: str | None = Header(default=None)):
    bridge_token = _tokens(authorization)
    target = _bridge_path(path)
    allowed = (request.method == "GET" and target != "/sessions" and not target.endswith("/messages")) or (request.method == "GET" and target == "/sessions") or (request.method == "POST" and (target == "/sessions" or target.endswith("/messages")))
    if not allowed:
        raise HTTPException(405, "method_not_allowed")
    headers = {"Authorization": f"Bearer {bridge_token}"}
    url = BRIDGE_URL + target
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
