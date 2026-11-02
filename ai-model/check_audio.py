"""
Check any audio file with the trained model (same maths as the watch).

    python check_audio.py some_clip.wav
    python check_audio.py ~/Desktop/recording_123.m4a     (a recording from the watch)

Prints the risk for every ~3 s of audio and the sounds YAMNet heard.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from safeguard_ai.audio import FRAMES_PER_WINDOW, frame_scores, load_audio, summarise
from safeguard_ai.yamnet import YamNet

HERE = Path(__file__).resolve().parent


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+", type=Path)
    ap.add_argument("--model-dir", type=Path, default=HERE / "output" / "android_assets" / "ai")
    args = ap.parse_args()

    head = json.loads((args.model_dir / "violence_head.json").read_text())
    yamnet = YamNet(args.model_dir / "yamnet.tflite")
    labels = (args.model_dir / "yamnet_labels.txt").read_text().splitlines()
    mean, scale = np.array(head["scaler_mean"]), np.array(head["scaler_scale"])
    coef, b, thr = np.array(head["coef"]), head["intercept"], head["threshold"]

    for path in args.files:
        scores = frame_scores(yamnet, load_audio(path))
        print(f"\n{path.name}  (alert threshold {thr:.2f})")
        worst = 0.0
        # Same as the watch: after each new frame, look at the last 5 frames.
        for i in range(len(scores)):
            window = scores[max(0, i - FRAMES_PER_WINDOW + 1): i + 1]
            feat = summarise(window)
            if head.get("feature_transform") == "log":
                feat = np.log(np.maximum(feat, 1e-6))
            z = float(((feat - mean) / scale) @ coef + b)
            p = 1 / (1 + np.exp(-z))
            worst = max(worst, p)
            top = np.argsort(scores[i])[::-1][:3]
            sounds = ", ".join(f"{labels[j]} {scores[i][j]:.2f}" for j in top)
            flag = "  <-- RISK" if p >= thr else ""
            print(f"  {i * 0.5:6.1f}s  risk {p:5.2f}  | {sounds}{flag}")
        print(f"  Highest risk: {worst:.2f} -> {'RISKY' if worst >= thr else 'not risky'}")


if __name__ == "__main__":
    main()
