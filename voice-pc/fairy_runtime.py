"""Shared original Fairy GPT-SoVITS model configuration for local tests."""

import os
from pathlib import Path
import re
import sys
from time import perf_counter


ROOT = Path(__file__).resolve().parent
ENGINE = ROOT / "vendor" / "GPT-SoVITS"
FAIRY = ROOT / "vendor" / "fairy-models" / "绝区零（已更新1.0）" / "Fairy"
PRETRAINED = ENGINE / "GPT_SoVITS" / "pretrained_models"
NLTK_DATA = ROOT / "vendor" / "nltk_data"
REFERENCE_TEXT = "了解我会如同主人使唤我一般使唤它，并且让它不敢有怨言。"
REFERENCE_WAV = FAIRY / f"{REFERENCE_TEXT}.wav"


def load_pipeline():
    required = (
        ENGINE, FAIRY / "Fairy-e10.ckpt", FAIRY / "Fairy_e10_s870.pth",
        REFERENCE_WAV, PRETRAINED / "chinese-hubert-base" / "pytorch_model.bin",
        PRETRAINED / "chinese-roberta-wwm-ext-large" / "pytorch_model.bin",
        ENGINE / "GPT_SoVITS" / "text" / "G2PWModel" / "g2pW.onnx",
        PRETRAINED / "fast_langdetect" / "lid.176.bin",
        NLTK_DATA / "corpora" / "cmudict",
        NLTK_DATA / "tokenizers" / "punkt",
        NLTK_DATA / "tokenizers" / "punkt_tab",
        NLTK_DATA / "taggers" / "averaged_perceptron_tagger",
        NLTK_DATA / "taggers" / "averaged_perceptron_tagger_eng",
    )
    for path in required:
        if not path.exists():
            raise FileNotFoundError(path)
    os.chdir(ENGINE)
    os.environ["NLTK_DATA"] = str(NLTK_DATA)
    sys.path.insert(0, str(ENGINE))
    sys.path.insert(0, str(ENGINE / "GPT_SoVITS"))
    import torch
    from GPT_SoVITS.TTS_infer_pack.TTS import TTS, TTS_Config

    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is unavailable; the Fairy PC test requires the GPU")
    config = TTS_Config({
        "version": "v2",
        "custom": {
            "version": "v2", "device": "cuda", "is_half": True,
            "t2s_weights_path": str(FAIRY / "Fairy-e10.ckpt"),
            "vits_weights_path": str(FAIRY / "Fairy_e10_s870.pth"),
            "bert_base_path": str(PRETRAINED / "chinese-roberta-wwm-ext-large"),
            "cnhuhbert_base_path": str(PRETRAINED / "chinese-hubert-base"),
        },
    })
    start = perf_counter()
    pipeline = TTS(config)
    # GPT-SoVITS selects this detector only for mixed-language text. Load it now
    # so a later answer never starts an implicit download or fails mid-stream.
    import fast_langdetect
    fast_langdetect.detect("DeepSeek AI", model="full")
    # The English G2P path also loads NLTK corpora lazily. A mixed-language
    # answer must work before the service starts accepting phone requests.
    from text.english import _g2p
    _g2p("DeepSeek AI")
    return pipeline, round((perf_counter() - start) * 1000)


def choose_text_language(text: str) -> str:
    has_chinese = bool(re.search(r"[\u4e00-\u9fff]", text))
    has_english = bool(re.search(r"[A-Za-z]", text))
    if has_chinese and has_english:
        return "zh"  # Mixed-language segmentation keeps English words intact.
    if has_english:
        return "en"
    return "all_zh"


def build_request(text: str, text_split_method: str = "cut5", text_lang: str | None = None) -> dict:
    return {
        "text": text,
        "text_lang": text_lang or choose_text_language(text),
        "ref_audio_path": str(REFERENCE_WAV),
        "prompt_text": REFERENCE_TEXT,
        "prompt_lang": "all_zh",
        "text_split_method": text_split_method,
        "return_fragment": True,
        "parallel_infer": True,
    }
