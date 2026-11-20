"""Read-only live check of the mobile gateway against the current DSH Host."""

import asyncio
import secrets
from pathlib import Path

import httpx
from fastapi import FastAPI

from agent_gateway import MOBILE_TOKEN_FILE, router

TEST_SESSION_FILE = Path("D:/dsh-data/workspace/hifairy-agent-probe/session-id.txt")


async def main():
    MOBILE_TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
    if not MOBILE_TOKEN_FILE.exists():
        MOBILE_TOKEN_FILE.write_text(secrets.token_hex(32), encoding="ascii")
    token = MOBILE_TOKEN_FILE.read_text(encoding="ascii").strip()
    app = FastAPI()
    app.include_router(router)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://local") as client:
        unauthorized = await client.get("/agent/sessions")
        authorized = await client.get("/agent/sessions", headers={"Authorization": f"Bearer {token}"})
        session_id = TEST_SESSION_FILE.read_text(encoding="ascii").strip()
        snapshot = await client.get(f"/agent/sessions/{session_id}/snapshot", headers={"Authorization": f"Bearer {token}"})
        print({"unauthorized": unauthorized.status_code, "sessions": authorized.status_code,
               "count": len(authorized.json().get("items", [])) if authorized.status_code == 200 else None,
               "snapshot": snapshot.status_code,
               "snapshot_type": snapshot.json().get("type") if snapshot.status_code == 200 else None})
        assert (unauthorized.status_code, authorized.status_code, snapshot.status_code) == (401, 200, 200)


if __name__ == "__main__":
    asyncio.run(main())
