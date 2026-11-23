"""Regenerate the bundled YOUKAI greeting clips from the Fairy voice model."""

from pathlib import Path

import numpy as np
import soundfile as sf

from fairy_runtime import build_request, load_pipeline


GREETINGS = {
    "youkai_greet_morning.wav": "早上好，法厄同。",
    "youkai_greet_afternoon.wav": "下午好，法厄同。",
    "youkai_greet_evening.wav": "晚上好，法厄同。",
}


def main() -> None:
    output = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res" / "raw"
    pipeline, load_ms = load_pipeline()
    print(f"Fairy model ready in {load_ms} ms", flush=True)
    for filename, greeting in GREETINGS.items():
        chunks = []
        sample_rate = None
        for sample_rate, samples in pipeline.run(build_request(greeting, "cut0")):
            if len(samples):
                chunks.append(samples)
        if not chunks or sample_rate is None:
            raise RuntimeError(f"No audio for {filename}")
        audio = np.concatenate(chunks)
        sf.write(output / filename, audio, sample_rate, subtype="PCM_16")
        print(f"{filename}: {len(audio) / sample_rate:.2f}s", flush=True)


if __name__ == "__main__":
    main()
