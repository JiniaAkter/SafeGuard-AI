"""Runs Google's pretrained YAMNet sound model (TensorFlow Lite version).

YAMNet recognises 521 everyday sound classes (speech, shouting, screaming,
crying, slap, glass breaking, ...). We use its class scores as the input to our
own small violence classifier.

The SAME yamnet.tflite file is copied into the watch app, so the watch computes
exactly the same numbers as this script.
"""
from __future__ import annotations

import shutil
import urllib.request
import zipfile
from pathlib import Path

import numpy as np

SAMPLE_RATE = 16_000          # YAMNet needs 16 kHz mono
FRAME_SAMPLES = 15_600        # one YAMNet input = 0.975 s
HOP_SAMPLES = 8_000           # a new frame every 0.5 s

YAMNET_URL = (
    "https://storage.googleapis.com/download.tensorflow.org/models/tflite/"
    "task_library/audio_classification/android/lite-model_yamnet_classification_tflite_1.tflite"
)


def _interpreter_class():
    """Use whichever TensorFlow Lite runtime is installed."""
    try:
        from ai_edge_litert.interpreter import Interpreter
        return Interpreter
    except ImportError:
        pass
    try:
        from tflite_runtime.interpreter import Interpreter
        return Interpreter
    except ImportError:
        pass
    try:
        import tensorflow as tf
        return tf.lite.Interpreter
    except ImportError:
        raise SystemExit(
            "No TensorFlow Lite runtime found.\n"
            "Install one with:  pip install ai-edge-litert   (or: pip install tensorflow)"
        )


def ensure_yamnet(path: Path) -> Path:
    """Downloads yamnet.tflite if it is not there yet."""
    path = Path(path)
    if path.exists():
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading YAMNet model to {path} ...")
    try:
        with urllib.request.urlopen(YAMNET_URL, timeout=60) as r, open(path, "wb") as f:
            shutil.copyfileobj(r, f)
    except Exception as e:  # noqa: BLE001
        if path.exists():
            path.unlink()
        raise SystemExit(
            f"Could not download YAMNet automatically ({e}).\n"
            "Download it by hand:\n"
            f"  1. Open {YAMNET_URL}\n"
            "     (or Kaggle Models > google/yamnet > TensorFlow Lite > classification-tflite)\n"
            f"  2. Save the file as: {path}\n"
        )
    print("Done.")
    return path


def read_labels(model_path: Path) -> list[str]:
    """The official YAMNet .tflite has its label list packed inside it (zip metadata)."""
    try:
        with zipfile.ZipFile(model_path) as z:
            for name in z.namelist():
                if name.endswith(".txt"):
                    lines = z.read(name).decode("utf-8").splitlines()
                    labels = [l.strip() for l in lines if l.strip()]
                    if len(labels) >= 500:
                        return labels
    except zipfile.BadZipFile:
        pass
    return []


class YamNet:
    def __init__(self, model_path: Path):
        Interpreter = _interpreter_class()
        self.model_path = Path(model_path)
        self.interpreter = Interpreter(model_path=str(model_path))
        inp = self.interpreter.get_input_details()[0]
        if int(np.prod(inp["shape"])) != FRAME_SAMPLES:
            self.interpreter.resize_tensor_input(inp["index"], [FRAME_SAMPLES])
        self.interpreter.allocate_tensors()
        inp = self.interpreter.get_input_details()[0]
        self._in_index = inp["index"]
        self._in_shape = inp["shape"]
        self._out_index = self.interpreter.get_output_details()[0]["index"]
        self.labels = read_labels(self.model_path)

    def scores(self, frame: np.ndarray) -> np.ndarray:
        """frame: 15 600 float samples in [-1, 1] -> 521 class scores (0..1)."""
        x = np.asarray(frame, dtype=np.float32).reshape(self._in_shape)
        self.interpreter.set_tensor(self._in_index, x)
        self.interpreter.invoke()
        return self.interpreter.get_tensor(self._out_index).reshape(-1).astype(np.float32)

    def label(self, i: int) -> str:
        return self.labels[i] if i < len(self.labels) else f"class {i}"
