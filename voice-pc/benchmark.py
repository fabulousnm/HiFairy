"""Run the original Fairy GPT-SoVITS weights locally and time synthesis.

Usage: <venv>/Scripts/python.exe voice-pc/benchmark.py
"""

import argparse
import json
import os
from pathlib import Path
import sys
from time import perf_counter


ROOT = Path(__file__).resolve().parent
ENGINE = ROOT / "vendor" / "GPT-SoVITS"
FAIRY = ROOT / "vendor" / "fairy-models" / "绝区零（已更新1.0）" / "Fairy"
PRETRAINED = ENGINE / "GPT_SoVITS" / "pretrained_models"
REFERENCE_TEXT = "了解我会如同主人使唤我一般使唤它，并且让它不敢有怨言。"
REFERENCE_WAV = FAIRY / f"{REFERENCE_TEXT}.wav"


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--text", default="主人，语音模块已就绪。今天有什么任务需要本系统处理？")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--text-split-method", choices=("cut0", "cut3", "cut5"), default="cut0")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "output")
    return parser.parse_args()


def main():
    args = parse_args()
    if args.repeats < 1:
        raise ValueError("--repeats must be positive")
    for path in (
        ENGINE, FAIRY / "Fairy-e10.ckpt", FAIRY / "Fairy_e10_s870.pth",
        REFERENCE_WAV, PRETRAINED / "chinese-hubert-base" / "pytorch_model.bin",
        PRETRAINED / "chinese-roberta-wwm-ext-large" / "pytorch_model.bin",
    ):
        if not path.exists():
            raise FileNotFoundError(path)

    os.chdir(ENGINE)
    sys.path.insert(0, str(ENGINE))
    sys.path.insert(0, str(ENGINE / "GPT_SoVITS"))
    start = perf_counter()
    import numpy as np
    import soundfile as sf
    import torch
    from GPT_SoVITS.TTS_infer_pack.TTS import TTS, TTS_Config

    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is unavailable; this benchmark requires the computer GPU")

    config = TTS_Config({
        "version": "v2",
        "custom": {
            "version": "v2",
            "device": "cuda",
            "is_half": True,
            "t2s_weights_path": str(FAIRY / "Fairy-e10.ckpt"),
            "vits_weights_path": str(FAIRY / "Fairy_e10_s870.pth"),
            "bert_base_path": str(PRETRAINED / "chinese-roberta-wwm-ext-large"),
            "cnhuhbert_base_path": str(PRETRAINED / "chinese-hubert-base"),
        },
    })
    model_start = perf_counter()
    pipeline = TTS(config)
    model_load_ms = round((perf_counter() - model_start) * 1000)
    startup_to_ready_ms = round((perf_counter() - start) * 1000)
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    runs = []
    for index in range(args.repeats):
        request = {
            "text": args.text,
            "text_lang": "all_zh",
            "ref_audio_path": str(REFERENCE_WAV),
            "prompt_text": REFERENCE_TEXT,
            "prompt_lang": "all_zh",
            "text_split_method": args.text_split_method,
            "return_fragment": True,
            "parallel_infer": True,
        }
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
        "runs": runs,
    }
    (output_dir / "metrics.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
