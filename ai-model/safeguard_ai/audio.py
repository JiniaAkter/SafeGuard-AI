"""Audio loading, framing, features and degraded-audio simulation."""
from __future__ import annotations

from math import gcd
from pathlib import Path

import numpy as np
from scipy.signal import butter, fftconvolve, resample_poly, sosfilt

from .yamnet import FRAME_SAMPLES, HOP_SAMPLES, SAMPLE_RATE

# The watch keeps the last 5 YAMNet frames (about 3 seconds of sound) and
# summarises them as mean + max of every class score. Training does the same.
FRAMES_PER_WINDOW = 5
WINDOW_HOP_FRAMES = 3          # training windows overlap: a new one every 1.5 s

AUDIO_EXTENSIONS = {".wav", ".mp3", ".flac", ".ogg", ".m4a", ".aac", ".aiff", ".aif"}


# ----------------------------------------------------------------------- loading

def _resample(y: np.ndarray, sr: int) -> np.ndarray:
    if sr == SAMPLE_RATE:
        return y
    g = gcd(sr, SAMPLE_RATE)
    return resample_poly(y, SAMPLE_RATE // g, sr // g)


def load_audio(path: Path) -> np.ndarray:
    """Returns 16 kHz mono float32 audio in [-1, 1]."""
    path = Path(path)
    try:
        import librosa  # handles wav, mp3, flac, ogg, m4a ...
        y, _ = librosa.load(str(path), sr=SAMPLE_RATE, mono=True)
        return y.astype(np.float32)
    except ImportError:
        pass

    try:
        import soundfile as sf
        y, sr = sf.read(str(path), dtype="float32", always_2d=True)
        return _resample(y.mean(axis=1), sr).astype(np.float32)
    except ImportError:
        pass

    from scipy.io import wavfile
    sr, y = wavfile.read(str(path))
    if y.dtype == np.int16:
        y = y / 32768.0
    elif y.dtype == np.int32:
        y = y / 2147483648.0
    elif y.dtype == np.uint8:
        y = (y.astype(np.float32) - 128) / 128.0
    y = np.asarray(y, dtype=np.float32)
    if y.ndim > 1:
        y = y.mean(axis=1)
    return _resample(y, sr).astype(np.float32)


# ----------------------------------------------------------------------- features

def frame_scores(yamnet, y: np.ndarray) -> np.ndarray:
    """YAMNet scores for every 0.975 s frame, one frame every 0.5 s -> (n_frames, 521)."""
    if len(y) < FRAME_SAMPLES:
        y = np.pad(y, (0, FRAME_SAMPLES - len(y)))
    starts = range(0, len(y) - FRAME_SAMPLES + 1, HOP_SAMPLES)
    return np.stack([yamnet.scores(y[s:s + FRAME_SAMPLES]) for s in starts])


def summarise(frames: np.ndarray) -> np.ndarray:
    """Mean and max of each class over a group of frames -> one feature vector."""
    return np.concatenate([frames.mean(axis=0), frames.max(axis=0)]).astype(np.float32)


def window_features(scores: np.ndarray) -> np.ndarray:
    """Splits a file's frame scores into ~3 s windows -> (n_windows, 2 * 521)."""
    n = len(scores)
    if n <= FRAMES_PER_WINDOW:
        return summarise(scores)[None, :]
    starts = range(0, n - FRAMES_PER_WINDOW + 1, WINDOW_HOP_FRAMES)
    return np.stack([summarise(scores[s:s + FRAMES_PER_WINDOW]) for s in starts])


# ------------------------------------------------------------ degraded audio tests

def _peak_match(y: np.ndarray, ref: np.ndarray) -> np.ndarray:
    peak, ref_peak = np.max(np.abs(y)) + 1e-9, np.max(np.abs(ref)) + 1e-9
    return (y * (ref_peak / peak)).astype(np.float32)


def add_noise(y: np.ndarray, snr_db: float, rng: np.random.Generator) -> np.ndarray:
    power = float(np.mean(y ** 2)) + 1e-12
    noise = rng.standard_normal(len(y)).astype(np.float32)
    noise *= np.sqrt(power / (10 ** (snr_db / 10)) / (np.mean(noise ** 2) + 1e-12))
    return np.clip(y + noise, -1, 1).astype(np.float32)


def phone_quality(y: np.ndarray) -> np.ndarray:
    """8 kHz narrow-band, like a phone call / cheap microphone."""
    low = resample_poly(y, 1, 2)
    return resample_poly(low, 2, 1)[: len(y)].astype(np.float32)


def far_away(y: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """Quieter, muffled (high frequencies lost) with some background hiss."""
    sos = butter(4, 3000, btype="low", fs=SAMPLE_RATE, output="sos")
    muffled = sosfilt(sos, y) * 0.25
    return add_noise(muffled.astype(np.float32), 25, rng)


def reverb(y: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """Echoey room (synthetic 0.4 s reverb tail)."""
    n = int(0.4 * SAMPLE_RATE)
    ir = rng.standard_normal(n) * np.exp(-np.linspace(0, 8, n))
    ir[0] = 1.0
    wet = fftconvolve(y, ir)[: len(y)]
    return _peak_match(wet, y)


def degrade(y: np.ndarray, condition: str, rng: np.random.Generator) -> np.ndarray:
    if condition == "clean":
        return y
    if condition == "noise_20dB":
        return add_noise(y, 20, rng)
    if condition == "noise_10dB":
        return add_noise(y, 10, rng)
    if condition == "noise_0dB":
        return add_noise(y, 0, rng)
    if condition == "phone_quality":
        return phone_quality(y)
    if condition == "far_away":
        return far_away(y, rng)
    if condition == "reverb":
        return reverb(y, rng)
    raise ValueError(condition)


DEGRADED_CONDITIONS = ["clean", "noise_20dB", "noise_10dB", "noise_0dB",
                       "phone_quality", "far_away", "reverb"]

CONDITION_DESCRIPTIONS = {
    "clean": "Original test audio",
    "noise_20dB": "Light background noise (SNR 20 dB)",
    "noise_10dB": "Moderate background noise (SNR 10 dB)",
    "noise_0dB": "Heavy background noise (noise as loud as the sound)",
    "phone_quality": "Low quality microphone (8 kHz)",
    "far_away": "Distant / muffled source",
    "reverb": "Echoey room (reverberation)",
}
