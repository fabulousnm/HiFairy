"""Run the original Fairy GPT-SoVITS weights locally and time synthesis.

Usage: <venv>/Scripts/python.exe voice-pc/benchmark.py
"""

import argparse
import json
from pathlib import Path
from time import perf_counter

from fairy_runtime import ROOT, build_request, load_pipeline



def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--text", default="主人，语音模块已就绪。今天有什么任务需要本系统处理？")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--text-split-method", choices=("cut0", "cut3", "cut5"), default="cut0")
    parser.add_argument("--text-lang", choices=("all_zh", "zh", "en"), help="Override automatic Chinese/English selection")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "output")
    return parser.parse_args()


def main():
    args = parse_args()
    if args.repeats < 1:
        raise ValueError("--repeats must be positive")
    start = perf_counter()
    import numpy as np
    import soundfile as sf
    import torch
    pipeline, model_load_ms = load_pipeline()
    startup_to_ready_ms = round((perf_counter() - start) * 1000)
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    runs = []
    for index in range(args.repeats):
        request = build_request(args.text, args.text_split_method, args.text_lang)
        begin = perf_counter()
        first_ms = None
        chunks = []
        sample_rate = None
        for rate, samples in pipeline.run(request):
            if first_ms is None and len(samples):
                torch.cuda.synchronize()
                first_ms = round((perf_counter() - begin) * 1000)
            sample_rate = rate
            chunks.append(samples)
        torch.cuda.synchronize()
        inference_ms = round((perf_counter() - begin) * 1000)
        if not chunks or sample_rate is None:
            raise RuntimeError("TTS returned no audio")
        audio = np.concatenate(chunks)
        duration_ms = round(len(audio) * 1000 / sample_rate)
        path = output_dir / f"fairy-{index + 1}.wav"
        sf.write(path, audio, sample_rate, subtype="PCM_16")
        result = {
            "run": index + 1,
            "first_chunk_ms": first_ms,
            "inference_ms": inference_ms,
            "audio_ms": duration_ms,
            "rtf": round(inference_ms / duration_ms, 3),
            "sample_rate": sample_rate,
            "output": str(path),
        }
        runs.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    summary = {
        "engine_commit": "d7c2210da8c013e81a94bfc7b811a477c99fd506",
        "torch": torch.__version__,
        "gpu": torch.cuda.get_device_name(0),
        "model_load_ms": model_load_ms,
        "startup_to_ready_ms": startup_to_ready_ms,
        "peak_allocated_vram_mb": round(torch.cuda.max_memory_allocated() / 1024**2),
        "text": args.text,
        "text_split_method": args.text_split_method,
        "text_lang": args.text_lang or build_request(args.text)["text_lang"],
        "runs": runs,
    }
    (output_dir / "metrics.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
