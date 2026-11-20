"""Verify live DSH deltas over the same WebSocket origin as Fairy voice."""

import argparse
import asyncio
import json
from pathlib import Path
from time import perf_counter

import httpx
import websockets

TOKEN_FILE = Path(__file__).parent / "vendor" / "agent-mobile-token.txt"
SESSION_FILE = Path("D:/dsh-data/workspace/hifairy-agent-probe/session-id.txt")


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True, help="Base URL ending in /agent")
    parser.add_argument("--long", action="store_true", help="Ask for a longer answer to expose buffering")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    socket_base = base.replace("https://", "wss://", 1).replace("http://", "ws://", 1)
    token = TOKEN_FILE.read_text(encoding="ascii").strip()
    session = SESSION_FILE.read_text(encoding="ascii").strip()
    async with websockets.connect(f"{socket_base}/live/{session}", open_timeout=12) as rejected:
        await rejected.send(json.dumps({"token": "invalid"}))
        try:
            await asyncio.wait_for(rejected.recv(), timeout=5)
            raise AssertionError("invalid token was accepted")
        except websockets.exceptions.ConnectionClosed as error:
            assert error.code == 1008, error.code
    async with websockets.connect(f"{socket_base}/live/{session}", max_size=8 * 1024 * 1024, open_timeout=12) as socket:
        await socket.send(json.dumps({"token": token}))
        first = json.loads(await asyncio.wait_for(socket.recv(), timeout=15))
        assert first.get("type") == "snapshot", first.get("type")
        started = perf_counter()
        prompt = ("HiFairy WebSocket 长回答流式验证。请按 1 到 15 编号，每条用一句完整中文说明一个手机与电脑协作的注意点。不要调用工具。"
                  if args.long else "HiFairy WebSocket 流式传输验证。请用三句话简短说明实时输出，不要调用工具。")
        async with httpx.AsyncClient(timeout=15) as client:
            response = await client.post(f"{base}/sessions/{session}/messages",
                                         headers={"Authorization": f"Bearer {token}"},
                                         json={"text": prompt})
            response.raise_for_status()
        first_delta = None
        last_delta = None
        final = None
        count = 0
        while True:
            frame = json.loads(await asyncio.wait_for(socket.recv(), timeout=25))
            now = perf_counter()
            if frame.get("type") == "assistant-stream" and frame.get("frame", {}).get("chunk", {}).get("type") == "text-delta":
                count += 1
                last_delta = now
                if first_delta is None:
                    first_delta = now
            if frame.get("event", {}).get("type") == "assistant/message":
                final = now
            if frame.get("event", {}).get("type") == "turn/end" and final is not None:
                break
        assert first_delta is not None and first_delta < final
        print({"first_delta_ms": round((first_delta - started) * 1000),
               "delta_span_ms": round((last_delta - first_delta) * 1000),
               "final_ms": round((final - started) * 1000),
               "delta_count": count, "streamed_before_final": True})


if __name__ == "__main__":
    asyncio.run(main())
