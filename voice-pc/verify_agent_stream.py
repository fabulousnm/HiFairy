"""Measure first DSH text delta versus final answer through the Agent gateway."""

import argparse
import json
import threading
from pathlib import Path
from time import perf_counter

import httpx

TOKEN_FILE = Path(__file__).parent / "vendor" / "agent-mobile-token.txt"
SESSION_FILE = Path("D:/dsh-data/workspace/hifairy-agent-probe/session-id.txt")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True, help="Base URL ending in /agent")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    token = TOKEN_FILE.read_text(encoding="ascii").strip()
    session = SESSION_FILE.read_text(encoding="ascii").strip()
    headers = {"Authorization": f"Bearer {token}"}
    started = [None]
    prompt_error = []

    def prompt():
        started[0] = perf_counter()
        try:
            response = httpx.post(f"{base}/sessions/{session}/messages", headers=headers,
                                  json={"text": "HiFairy 流式传输验证。请用三句话介绍实时事件流，每句话简短，不要调用工具。"}, timeout=15)
            response.raise_for_status()
        except Exception as error:
            prompt_error.append(str(error))

    first_delta = None
    final = None
    delta_count = 0
    worker = None
    with httpx.stream("GET", f"{base}/sessions/{session}/events", headers=headers, timeout=35) as response:
        response.raise_for_status()
        for line in response.iter_lines():
            if not line.startswith("data: "):
                continue
            frame = json.loads(line[6:])
            if frame.get("type") == "snapshot" and worker is None:
                worker = threading.Thread(target=prompt, daemon=True)
                worker.start()
                continue
            if started[0] is None:
                continue
            now = perf_counter()
            if frame.get("type") == "assistant-stream" and frame.get("frame", {}).get("chunk", {}).get("type") == "text-delta":
                delta_count += 1
                if first_delta is None:
                    first_delta = now
            event_type = frame.get("event", {}).get("type")
            if event_type == "assistant/message":
                final = now
            if event_type == "turn/end" and final is not None:
                break
    if worker is not None:
        worker.join(timeout=1)
    if prompt_error:
        raise RuntimeError(prompt_error[0])
    assert started[0] is not None and first_delta is not None and final is not None
    print({"first_delta_ms": round((first_delta - started[0]) * 1000),
           "final_ms": round((final - started[0]) * 1000), "delta_count": delta_count,
           "streamed_before_final": first_delta < final})


if __name__ == "__main__":
    main()
