#!/usr/bin/env python3
"""
SenseVoice-Small runner for Vato
Supports both standalone CLI and low-latency persistent daemon mode.
"""

import os
import sys
import json
import time
import urllib.request
from pathlib import Path

# ponytail: single worker process, pool if concurrent STT throughput spikes

DEFAULT_MODEL_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx"
DEFAULT_TOKENS_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt"

EMOTION_MAP = {
    "<|HAPPY|>": "[HAPPY]",
    "<|SAD|>": "[SAD]",
    "<|ANGRY|>": "[ANGRY]",
    "<|NEUTRAL|>": "[NEUTRAL]",
    "<|FEARFUL|>": "[FEARFUL]",
    "<|DISGUSTED|>": "[DISGUSTED]",
    "<|SURPRISED|>": "[SURPRISED]",
    "<|OTHER|>": "[NEUTRAL]",
    "<|EMO_UNKNOWN|>": "[NEUTRAL]",
}


def find_or_download_models():
    """Locate or download SenseVoice-Small ONNX model and tokens."""
    candidates = [
        os.environ.get("SENSEVOICE_MODEL_DIR"),
        os.path.expanduser("~/.cache/sherpa-onnx/sense-voice"),
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "models", "sense-voice"),
    ]
    model_dir = None
    for cand in candidates:
        if cand and os.path.isdir(cand):
            m_path = os.path.join(cand, "model.int8.onnx")
            if not os.path.exists(m_path):
                m_path = os.path.join(cand, "model.onnx")
            t_path = os.path.join(cand, "tokens.txt")
            if os.path.exists(m_path) and os.path.exists(t_path):
                return m_path, t_path

    # Fallback default cache dir
    cache_dir = os.path.expanduser("~/.cache/sherpa-onnx/sense-voice")
    os.makedirs(cache_dir, exist_ok=True)
    m_path = os.path.join(cache_dir, "model.int8.onnx")
    t_path = os.path.join(cache_dir, "tokens.txt")

    if not os.path.exists(m_path):
        sys.stderr.write(f"Downloading SenseVoice model to {m_path}...\n")
        urllib.request.urlretrieve(DEFAULT_MODEL_URL, m_path)
    if not os.path.exists(t_path):
        sys.stderr.write(f"Downloading SenseVoice tokens to {t_path}...\n")
        urllib.request.urlretrieve(DEFAULT_TOKENS_URL, t_path)

    return m_path, t_path


def normalize_emotion(raw_emotion, raw_event):
    """Normalize raw model tokens into standard emotion tags."""
    if not raw_emotion:
        raw_emotion = ""
    clean_emo = raw_emotion.strip()
    tag = EMOTION_MAP.get(clean_emo, "")

    # Event override: laughter / applause
    if raw_event:
        event_str = raw_event.strip().lower()
        if "laughter" in event_str:
            return "[LAUGHTER]"
        if "applause" in event_str:
            return "[APPLAUSE]"
        if "cry" in event_str:
            return "[SAD]"

    if not tag and clean_emo:
        inner = clean_emo.replace("<|", "").replace("|>", "").replace("[", "").replace("]", "").strip().upper()
        if inner:
            tag = f"[{inner}]"
    return tag or "[NEUTRAL]"


class SenseVoiceRunner:
    def __init__(self, num_threads=4):
        import sherpa_onnx
        m_path, t_path = find_or_download_models()
        self.recognizer = sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=m_path,
            tokens=t_path,
            num_threads=num_threads,
            language="en",
            use_itn=True,
        )

    def transcribe(self, audio_path, language=""):
        import soundfile as sf
        t0 = time.time()
        samples, sample_rate = sf.read(audio_path, dtype="float32")
        if samples.ndim > 1:
            samples = samples[:, 0]

        stream = self.recognizer.create_stream()
        stream.accept_waveform(sample_rate, samples)
        self.recognizer.decode_stream(stream)
        t_decode = time.time()

        res = stream.result
        clean_text = (res.text or "").strip()
        raw_emo = getattr(res, "emotion", "") or ""
        raw_event = getattr(res, "event", "") or ""
        lang = getattr(res, "lang", "") or ""

        emotion_tag = normalize_emotion(raw_emo, raw_event)

        # Preserve emotion tag in text prefix for prompt and UI badges if not already present
        if emotion_tag and not clean_text.startswith("["):
            formatted_text = f"{emotion_tag} {clean_text}".strip()
        else:
            formatted_text = clean_text

        return {
            "text": formatted_text,
            "clean_text": clean_text,
            "emotion": emotion_tag,
            "event": raw_event.replace("<|", "").replace("|>", "").strip() if raw_event else "",
            "lang": lang.replace("<|", "").replace("|>", "").strip() if lang else "",
            "duration_ms": round((t_decode - t0) * 1000, 2),
        }


def run_daemon(runner):
    sys.stdout.write("READY\n")
    sys.stdout.flush()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        if line in ("QUIT", "EXIT"):
            break
        try:
            res = runner.transcribe(line)
            sys.stdout.write(json.dumps(res) + "\n")
        except Exception as e:
            sys.stdout.write(json.dumps({"error": str(e), "text": "", "emotion": "[NEUTRAL]"}) + "\n")
        sys.stdout.flush()


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--daemon":
        runner = SenseVoiceRunner()
        run_daemon(runner)
        return

    if len(sys.argv) > 1 and sys.argv[1] in ("--test", "-t"):
        runner = SenseVoiceRunner()
        test_wav = "/tmp/en.wav"
        if not os.path.exists(test_wav):
            sys.stderr.write(f"Test file {test_wav} not found\n")
            sys.exit(1)
        res = runner.transcribe(test_wav)
        print(json.dumps(res, indent=2))
        return

    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <audio_file.wav> | --daemon | --test")
        sys.exit(1)

    audio_path = sys.argv[1]
    runner = SenseVoiceRunner()
    res = runner.transcribe(audio_path)
    print(json.dumps(res))


if __name__ == "__main__":
    main()
