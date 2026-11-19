"""Temporary token-protected Fairy streaming test server."""

import argparse
import asyncio
import hmac
import json
import secrets
from threading import Event
from pathlib import Path
from time import perf_counter

import numpy as np
from fastapi import FastAPI, WebSocket
from fastapi.responses import FileResponse
import uvicorn

from fairy_runtime import ROOT, build_request, load_pipeline


TOKEN_FILE = ROOT / "vendor" / "remote-test-token.txt"
PAGE = ROOT / "web_test.html"
app = FastAPI()
pipeline = None
token = None
generation_lock = asyncio.Lock()


@app.get("/")
async def index():
    return FileResponse(PAGE)


@app.get("/health")
async def health():
    return {"ready": pipeline is not None, "stream": "websocket"}


def generate(text, loop, queue, cancelled):
    start = perf_counter()
    try:
        first_ms = None
        samples_total = 0
        chunk_count = 0
        for rate, samples in pipeline.run(build_request(text)):
            if cancelled.is_set():
                break
            pcm = np.asarray(samples, dtype="<i2").tobytes()
            if not pcm:
                continue
            elapsed = round((perf_counter() - start) * 1000)
            if first_ms is None:
                first_ms = elapsed
            chunk_count += 1
            samples_total += len(samples)
            loop.call_soon_threadsafe(queue.put_nowait, ("audio", rate, pcm))
        loop.call_soon_threadsafe(queue.put_nowait, ("done", {
            "server_first_chunk_ms": first_ms,
            "server_total_ms": round((perf_counter() - start) * 1000),
            "audio_ms": round(samples_total * 1000 / rate) if chunk_count else 0,
            "chunks": chunk_count,
        }))
    except Exception as exc:
        loop.call_soon_threadsafe(queue.put_nowait, ("error", str(exc)))


@app.websocket("/stream")
async def stream(ws: WebSocket):
    await ws.accept()
    try:
        authenticated = False
        while True:
            request = json.loads(await ws.receive_text())
            supplied = request.get("token", "")
            text = request.get("text", "")
            if not authenticated:
                if not isinstance(supplied, str) or not hmac.compare_digest(supplied, token):
                    await ws.close(code=1008, reason="Invalid test token")
                    return
                authenticated = True
            if not isinstance(text, str) or not text.strip():
                await ws.send_json({"type": "error", "message": "请输入需要朗读的文字。"})
                continue
            async with generation_lock:
                queue = asyncio.Queue()
                cancelled = Event()
                worker = asyncio.create_task(asyncio.to_thread(generate, text.strip(), asyncio.get_running_loop(), queue, cancelled))
                try:
                    await ws.send_json({"type": "started"})
                    while True:
                        item = await queue.get()
                        if item[0] == "audio":
                            _, rate, pcm = item
                            await ws.send_json({"type": "audio", "sample_rate": rate})
                            await ws.send_bytes(pcm)
                        elif item[0] == "done":
                            await ws.send_json({"type": "done", **item[1]})
                            break
                        else:
                            await ws.send_json({"type": "error", "message": item[1]})
                            break
                finally:
                    cancelled.set()
                    if not worker.done():
                        pipeline.stop()
                    await worker
    except Exception as exc:
        print(f"WebSocket closed: {type(exc).__name__}: {exc}", flush=True)
    finally:
        try:
            await ws.close()
        except Exception:
            pass


def main():
    global pipeline, token
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
    if not TOKEN_FILE.exists():
        TOKEN_FILE.write_text(secrets.token_urlsafe(24), encoding="ascii")
    token = TOKEN_FILE.read_text(encoding="ascii").strip()
    pipeline, model_load_ms = load_pipeline()
    print(f"Fairy loaded in {model_load_ms} ms; warming up", flush=True)
    for _ in pipeline.run(build_request("主人，语音模块已就绪。")):
        pass
    print(f"Fairy ready on 127.0.0.1:{args.port}", flush=True)
    uvicorn.run(app, host="127.0.0.1", port=args.port, log_level="warning")


if __name__ == "__main__":
    main()
