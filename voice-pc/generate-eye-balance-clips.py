"""Generate the two bundled Fairy eye-balance responses through the local Fairy service."""

import asyncio
import json
from pathlib import Path
import wave

import websockets

from fairy_runtime import ROOT


CLIPS = {
    "fairy_balance_low.wav": "主人，余额不足，该让助手二号帮我充电费了。",
    "fairy_balance_ok.wav": "主人，电费还够，有什么想让我做的。",
}


async def generate() -> None:
    output = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res" / "raw"
    output.mkdir(parents=True, exist_ok=True)
    token = (ROOT / "vendor" / "remote-test-token.txt").read_text(encoding="ascii").strip()
    async with websockets.connect("ws://127.0.0.1:8765/stream", max_size=32 * 1024 * 1024) as ws:
        for filename, text in CLIPS.items():
            await ws.send(json.dumps({"token": token, "text": text}, ensure_ascii=False))
            chunks = []
            rate = None
            while True:
                message = await ws.recv()
                if isinstance(message, bytes):
                    chunks.append(message)
                    continue
                data = json.loads(message)
                if data["type"] == "audio":
                    rate = data["sample_rate"]
                elif data["type"] == "done":
                    break
                elif data["type"] == "error":
                    raise RuntimeError(data["message"])
            if not chunks or rate is None:
                raise RuntimeError(f"No audio for {filename}")
            with wave.open(str(output / filename), "wb") as audio:
                audio.setnchannels(1)
                audio.setsampwidth(2)
                audio.setframerate(rate)
                audio.writeframes(b"".join(chunks))
            print(f"{filename}: {sum(map(len, chunks)) / (rate * 2):.2f}s", flush=True)


if __name__ == "__main__":
    asyncio.run(generate())
