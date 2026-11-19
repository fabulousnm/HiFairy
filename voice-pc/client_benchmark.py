"""Measure WebSocket transport without opening a browser."""
import argparse
import asyncio
import json
from pathlib import Path
from time import perf_counter
import wave
from urllib.parse import urlparse

import websockets
from fairy_runtime import ROOT


async def run(args):
    token = (ROOT / "vendor" / "remote-test-token.txt").read_text(encoding="ascii").strip()
    started = perf_counter()
    connection = {"max_size": 32 * 1024 * 1024}
    if args.connect_host:
        connection["host"] = args.connect_host
        connection["port"] = 443
        connection["server_hostname"] = urlparse(args.url).hostname
    async with websockets.connect(args.url, **connection) as ws:
        connected_ms = round((perf_counter() - started) * 1000)
        await ws.send(json.dumps({"token": token, "text": args.text}, ensure_ascii=False))
        first_byte_ms = None
        chunks = []
        sample_rate = None
        async for message in ws:
            if isinstance(message, bytes):
                if first_byte_ms is None:
                    first_byte_ms = round((perf_counter() - started) * 1000)
                chunks.append(message)
            else:
                data = json.loads(message)
                if data["type"] == "audio":
                    sample_rate = data["sample_rate"]
                elif data["type"] == "done":
                    total_ms = round((perf_counter() - started) * 1000)
                    break
                elif data["type"] == "error":
                    raise RuntimeError(data["message"])
        else:
            raise RuntimeError("Connection closed before completion")
    if not chunks or sample_rate is None:
        raise RuntimeError("No PCM audio received")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(args.output), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(sample_rate)
        output.writeframes(b"".join(chunks))
    print(json.dumps({"connect_ms": connected_ms, "first_byte_ms": first_byte_ms,
                      "total_ms": total_ms, "chunks": len(chunks), "server": data,
                      "wav": str(args.output)}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("url", help="ws:// or wss:// endpoint ending /stream")
    parser.add_argument("--connect-host", help="Override local DNS for the TCP connection")
    parser.add_argument("--text", default="主人，语音模块已就绪。今天有什么任务需要本系统处理？")
    parser.add_argument("--output", type=Path, default=ROOT / "output" / "transport-test.wav")
    asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    main()
