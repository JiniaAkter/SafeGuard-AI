"""
SafeGuardAI – train and test the audio violence detector.

    python train_and_evaluate.py --data ~/Downloads/audio-violence-dataset

What it does
  1. Finds the labelled audio files (violent / non-violent).
  2. Runs Google's pretrained YAMNet model on every file (0.975 s frames, every 0.5 s).
  3. Turns each ~3 s of audio into one feature vector (mean + max of the 521 YAMNet scores).
  4. Trains a small classifier (logistic regression) on top – "transfer learning".
  5. Tests it on files it has never seen: precision, recall, F1, confusion matrix,
     false positives / negatives.
  6. Repeats the test on degraded audio (noise, phone quality, distance, echo).
  7. Exports everything the watch app needs into  output/android_assets/ai/

Results are written to output/report/ (report.md, metrics.json, confusion_matrix.png,
errors.csv).
"""
from __future__ import annotations

import argparse
import csv
import json
import pickle
import shutil
import sys
import time
from datetime import datetime
from pathlib import Path

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (accuracy_score, confusion_matrix, f1_score, precision_score,
                             recall_score, roc_auc_score)
from sklearn.model_selection import GroupKFold, train_test_split
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from safeguard_ai.audio import (CONDITION_DESCRIPTIONS, DEGRADED_CONDITIONS, FRAMES_PER_WINDOW,
                                WINDOW_HOP_FRAMES, degrade, frame_scores, load_audio,
                                summarise, window_features)
from safeguard_ai.dataset import VIOLENT, find_dataset, load_vsd
from safeguard_ai.yamnet import (FRAME_SAMPLES, HOP_SAMPLES, SAMPLE_RATE, YamNet, ensure_yamnet,
                                 read_labels)

HERE = Path(__file__).resolve().parent

# Each feature window covers ~3 s of audio; a new window starts every 1.5 s.
WINDOW_SEC = (FRAMES_PER_WINDOW - 1) * HOP_SAMPLES / SAMPLE_RATE + FRAME_SAMPLES / SAMPLE_RATE
STEP_SEC = WINDOW_HOP_FRAMES * HOP_SAMPLES / SAMPLE_RATE
MIN_OVERLAP_SEC = 1.0   # a window counts as violent if >= 1 s of it is labelled violent


def window_labels(item, n_windows: int) -> tuple[np.ndarray, np.ndarray]:
    """
    Label for every ~3 s window of a file, plus a mask of windows to use.
    Whole-file labels: every window gets the file's label.
    Time-range labels (VSD sheet): violent if it overlaps violence by >= 1 s,
    calm if it doesn't overlap at all, and skipped if it only touches a little.
    """
    if item.intervals is None:
        return np.full(n_windows, item.label), np.ones(n_windows, dtype=bool)
    labels = np.zeros(n_windows, dtype=int)
    keep = np.ones(n_windows, dtype=bool)
    for k in range(n_windows):
        t0 = k * STEP_SEC
        t1 = t0 + WINDOW_SEC
        overlap = sum(max(0.0, min(t1, e) - max(t0, s)) for s, e in item.intervals)
        if overlap >= MIN_OVERLAP_SEC:
            labels[k] = 1
        elif overlap > 0:
            keep[k] = False
    return labels, keep


# ----------------------------------------------------------------------------- helpers

def progress(i: int, n: int, start: float, what: str) -> None:
    if i == 0 or (i + 1) % 20 == 0 or i + 1 == n:
        elapsed = time.time() - start
        eta = elapsed / (i + 1) * (n - i - 1)
        print(f"  {what}: {i + 1}/{n}   (about {eta / 60:.1f} min left)", flush=True)


def compute_metrics(y_true: np.ndarray, prob: np.ndarray, threshold: float) -> dict:
    y_true = np.asarray(y_true).astype(int)
    pred = (np.asarray(prob) >= threshold).astype(int)
    tn, fp, fn, tp = confusion_matrix(y_true, pred, labels=[0, 1]).ravel()
    out = {
        "samples": int(len(y_true)),
        "precision": float(precision_score(y_true, pred, zero_division=0)),
        "recall": float(recall_score(y_true, pred, zero_division=0)),
        "f1": float(f1_score(y_true, pred, zero_division=0)),
        "accuracy": float(accuracy_score(y_true, pred)),
        "true_positives": int(tp), "false_positives": int(fp),
        "true_negatives": int(tn), "false_negatives": int(fn),
        "false_positive_rate": float(fp / (fp + tn)) if (fp + tn) else 0.0,
    }
    if len(set(y_true.tolist())) == 2:
        out["roc_auc"] = float(roc_auc_score(y_true, prob))
    return out


def best_threshold(y: np.ndarray, prob: np.ndarray, min_precision: float) -> float:
    """Highest F1 (or highest recall while keeping precision >= min_precision)."""
    thresholds = np.round(np.linspace(0.05, 0.95, 91), 2)
    scores = []
    for t in thresholds:
        m = compute_metrics(y, prob, t)
        if min_precision > 0:
            scores.append(m["recall"] if m["precision"] >= min_precision else -1 + m["precision"])
        else:
            scores.append(m["f1"])
    scores = np.array(scores)
    # Several thresholds often tie – take the middle of the best range (more robust).
    best = thresholds[scores >= scores.max() - 1e-9]
    return float(best[len(best) // 2])


def stack(features: dict, items: list) -> tuple[np.ndarray, np.ndarray]:
    xs, ys = [], []
    for item in items:
        f = features[str(item.path)]
        labels, keep = window_labels(item, len(f))
        xs.append(f[keep])
        ys.append(labels[keep])
    return np.concatenate(xs), np.concatenate(ys)


def stack_groups(features: dict, items: list) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Like stack(), plus the file number of every window (for cross-validation by file)."""
    xs, ys, gs = [], [], []
    for n, item in enumerate(items):
        f = features[str(item.path)]
        labels, keep = window_labels(item, len(f))
        xs.append(f[keep])
        ys.append(labels[keep])
        gs.append(np.full(int(keep.sum()), n))
    return np.concatenate(xs), np.concatenate(ys), np.concatenate(gs)


def apply_transform(x: np.ndarray, kind: str) -> np.ndarray:
    """YAMNet scores are tiny (0.001 …); their logarithm is easier for a linear model."""
    return np.log(np.maximum(x, 1e-6)) if kind == "log" else x


def make_model(c: float):
    return make_pipeline(StandardScaler(),
                         LogisticRegression(C=c, class_weight="balanced", max_iter=5000))


def frame_features(frames: np.ndarray) -> np.ndarray:
    """The watch's features: after every 0.5 s frame, summarise the last ~3 s."""
    return np.stack([summarise(frames[max(0, i - FRAMES_PER_WINDOW + 1): i + 1])
                     for i in range(len(frames))])


def events_from_probs(probs: np.ndarray, item, threshold: float, confirm: int) -> dict:
    """
    Replays a recording exactly like the watch: a risk result every 0.5 s, and an
    ALERT after [confirm] results in a row above the threshold (confirm 2 ≈ 1 s).
    Alerts less than 10 s apart count as one alert.
    """
    hop = HOP_SAMPLES / SAMPLE_RATE
    frame_len = FRAME_SAMPLES / SAMPLE_RATE
    triggers, streak = [], 0
    for i, p in enumerate(probs):
        streak = streak + 1 if p >= threshold else 0
        if streak >= confirm:
            streak = 0
            triggers.append(i * hop + frame_len)   # time the watch would raise the alert

    duration = (len(probs) - 1) * hop + frame_len
    if item.intervals is not None:
        spans = [(s, e) for s, e in item.intervals if e - s >= 2.0]
    else:
        spans = [(0.0, duration)] if item.label else []
    detected, delays = 0, []
    for s, e in spans:
        hits = [t for t in triggers if s <= t <= e + 3]
        if hits:
            detected += 1
            delays.append(hits[0] - s)
    # False alerts: triggers outside violence; ones less than 10 s apart count as one.
    false_alerts, last = 0, -1e9
    for t in triggers:
        if any(s - 1 <= t <= e + 3 for s, e in spans):
            continue
        if t - last > 10:
            false_alerts += 1
        last = t
    violent_secs = sum(min(e, duration) - max(s, 0) for s, e in spans if e > 0)
    return {"violent_events": len(spans), "detected": detected, "false_alerts": false_alerts,
            "calm_seconds": max(0.0, duration - violent_secs), "delays": delays}


def total_events(file_probs: list, threshold: float, confirm: int) -> dict:
    """Adds up events_from_probs over many (probs, item) pairs."""
    t = {"violent_events": 0, "detected": 0, "false_alerts": 0, "calm_seconds": 0.0, "delays": []}
    for probs, item in file_probs:
        ev = events_from_probs(probs, item, threshold, confirm)
        for k in ("violent_events", "detected", "false_alerts", "calm_seconds"):
            t[k] += ev[k]
        t["delays"] += ev["delays"]
    hours = t["calm_seconds"] / 3600
    return {
        "violent_events": t["violent_events"],
        "violent_events_detected": t["detected"],
        "event_recall": t["detected"] / t["violent_events"] if t["violent_events"] else 0.0,
        "false_alerts": t["false_alerts"],
        "calm_hours": round(hours, 3),
        "false_alerts_per_hour": t["false_alerts"] / hours if hours else 0.0,
        "median_seconds_to_alert": float(np.median(t["delays"])) if t["delays"] else None,
    }


def split_files(items: list, test_size: float, seed: int) -> tuple[list, list]:
    """Splits by FILE, keeping the violent / calm mix similar when possible."""
    idx = np.arange(len(items))
    labels = np.array([i.label for i in items])
    try:
        a, b = train_test_split(idx, test_size=test_size, stratify=labels, random_state=seed)
    except ValueError:  # too few files of one kind to stratify
        a, b = train_test_split(idx, test_size=test_size, random_state=seed)
    return [items[i] for i in a], [items[i] for i in b]


# ----------------------------------------------------------------------------- main

def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", required=True, type=Path, help="Folder of the unzipped Kaggle dataset")
    ap.add_argument("--labels-csv", type=Path, help="Optional CSV with file + label columns")
    ap.add_argument("--vsd-sheet", type=Path,
                    help="VSD.xlsx with violent time ranges (found automatically if inside --data)")
    ap.add_argument("--yamnet", type=Path, default=HERE / "models" / "yamnet.tflite")
    ap.add_argument("--out", type=Path, default=HERE / "output")
    ap.add_argument("--min-precision", type=float, default=0.0,
                    help="e.g. 0.85 = pick the alert threshold so at least 85%% of alerts are correct")
    ap.add_argument("--max-false-alerts", type=float, default=5.0,
                    help="Target: at most this many false alerts per hour of calm audio (default 5)")
    ap.add_argument("--max-files", type=int, default=0, help="Use at most N files per class (quick trial run)")
    ap.add_argument("--skip-degraded", action="store_true", help="Skip the noisy / degraded audio tests")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    out = args.out
    report_dir, assets_dir = out / "report", out / "android_assets" / "ai"
    report_dir.mkdir(parents=True, exist_ok=True)
    assets_dir.mkdir(parents=True, exist_ok=True)

    # 1. dataset -----------------------------------------------------------------
    print("\n[1/6] Finding dataset files ...")
    sheet = args.vsd_sheet
    if sheet is None and not args.labels_csv:
        sheet = next(iter(sorted(Path(args.data).rglob("*.xlsx"))), None)
    if sheet is not None:
        print(f"  Using time-range labels from {sheet.name}")
        items = load_vsd(args.data, sheet)
    else:
        items = find_dataset(args.data, args.labels_csv)
    if args.max_files:
        rng = np.random.default_rng(args.seed)
        keep = []
        for label in (0, 1):
            group = [i for i in items if i.label == label]
            rng.shuffle(group)
            keep += group[: args.max_files]
        items = keep
        print(f"  Using {len(items)} files (--max-files {args.max_files})")

    # 2. YAMNet ------------------------------------------------------------------
    print("\n[2/6] Loading pretrained YAMNet ...")
    model_path = ensure_yamnet(args.yamnet)
    yamnet = YamNet(model_path)
    print(f"  {model_path.name}: {len(yamnet.labels) or 521} sound classes")

    # 3. YAMNet scores for every 0.5 s of every file (cached) -----------------------
    print("\n[3/6] Running YAMNet on every file (cached after the first run) ...")
    cache_path = out / "frames_cache.pkl"
    cache = pickle.loads(cache_path.read_bytes()) if cache_path.exists() else {}
    frames_by, features, failed = {}, {}, []
    start = time.time()
    for n, item in enumerate(items):
        key = str(item.path)
        stamp = (item.path.stat().st_mtime, item.path.stat().st_size, model_path.stat().st_size)
        if key in cache and cache[key][0] == stamp:
            frames = cache[key][1]
        else:
            try:
                frames = frame_scores(yamnet, load_audio(item.path))
            except Exception as e:  # noqa: BLE001
                failed.append((item.path, e))
                continue
            cache[key] = (stamp, frames)
        frames_by[key] = frames
        features[key] = window_features(frames)
        progress(n, len(items), start, "files")
    cache_path.write_bytes(pickle.dumps(cache))
    if failed:
        print(f"  Skipped {len(failed)} unreadable files, e.g. {failed[0][0].name}: {failed[0][1]}")
    items = [i for i in items if str(i.path) in features]

    # 4. split by FILE (never put windows of one file in both train and test) -----
    print("\n[4/6] Splitting into training and test files ...")
    test_items = [i for i in items if i.split == "test"]
    if test_items:
        train_items = [i for i in items if i.split != "test"]
        print("  Using the dataset's own test set")
    else:
        train_items, test_items = split_files(items, 0.2, args.seed)

    x_tr, y_tr, g_tr = stack_groups(features, train_items)
    x_te, y_te, _ = stack_groups(features, test_items)
    for name, group, ys in (("train", train_items, y_tr), ("test", test_items, y_te)):
        print(f"  {name:<6} {len(group):>4} files -> {len(ys):>5} windows "
              f"({int(ys.sum())} violent, {int(len(ys) - ys.sum())} calm)")
    if len(set(y_tr.tolist())) < 2:
        raise SystemExit("Training data has only one class – need more files.")

    # 5. train: choose settings with 5-fold cross-validation (by file) ------------
    print("\n[5/6] Training the classifier on top of YAMNet (5-fold cross-validation) ...")
    n_folds = max(2, min(5, len(train_items)))
    folds = list(GroupKFold(n_splits=n_folds).split(x_tr, y_tr, g_tr))
    best = None
    for kind in ("log", "none"):
        xk = apply_transform(x_tr, kind)
        for c in (0.003, 0.01, 0.03, 0.1, 0.3):
            oof = np.full(len(y_tr), np.nan)
            for tr, va in folds:
                if len(set(y_tr[tr].tolist())) < 2:
                    continue
                oof[va] = make_model(c).fit(xk[tr], y_tr[tr]).predict_proba(xk[va])[:, 1]
            ok = ~np.isnan(oof)
            t = best_threshold(y_tr[ok], oof[ok], args.min_precision)
            m = compute_metrics(y_tr[ok], oof[ok], t)
            print(f"  features={kind:<4} C={c:<6} CV F1={m['f1']:.3f}  precision={m['precision']:.3f}"
                  f"  recall={m['recall']:.3f}  (threshold {t:.2f})")
            if best is None or m["f1"] > best[0]:
                best = (m["f1"], kind, c, t, m)
    _, kind, best_c, threshold, cv_metrics = best
    model = make_model(best_c).fit(apply_transform(x_tr, kind), y_tr)
    print(f"  Chosen: features={kind}, C={best_c}, alert threshold={threshold:.2f}")

    def predict(x: np.ndarray) -> np.ndarray:
        return model.predict_proba(apply_transform(x, kind))[:, 1]

    # 5b. choose WHEN the watch alerts (threshold + how long the risk must last),
    # using out-of-fold predictions on the training files only.
    print(f"  Choosing the alert setting (target: <= {args.max_false_alerts:g} false alerts/hour) ...")
    xk = apply_transform(x_tr, kind)
    oof_files = []
    for tr, va in folds:
        if len(set(y_tr[tr].tolist())) < 2:
            continue
        fold_model = make_model(best_c).fit(xk[tr], y_tr[tr])
        for n in sorted(set(g_tr[va].tolist())):
            item = train_items[n]
            feats = apply_transform(frame_features(frames_by[str(item.path)]), kind)
            oof_files.append((fold_model.predict_proba(feats)[:, 1], item))
    options = []
    for confirm in (2, 3, 4, 6, 8):
        for thr in np.round(np.arange(0.30, 0.96, 0.05), 2):
            ev = total_events(oof_files, float(thr), confirm)
            options.append((confirm, float(thr), ev))
    ok = [o for o in options if o[2]["false_alerts_per_hour"] <= args.max_false_alerts]
    if ok:
        confirm, alert_threshold, cv_events = max(
            ok, key=lambda o: (o[2]["event_recall"], -o[2]["false_alerts_per_hour"], o[1]))
    else:
        confirm, alert_threshold, cv_events = min(
            options, key=lambda o: (o[2]["false_alerts_per_hour"], -o[2]["event_recall"]))
        print("  Note: no setting reached the false-alert target; using the one with fewest.")
    for c in (2, 3, 4, 6, 8):
        best_c_opt = max((o for o in options if o[0] == c and o in ok), default=None,
                         key=lambda o: o[2]["event_recall"])
        if best_c_opt:
            e = best_c_opt[2]
            print(f"    risk for {c * 0.5:.1f} s: best threshold {best_c_opt[1]:.2f} -> "
                  f"{e['event_recall']:.0%} events caught, {e['false_alerts_per_hour']:.1f} false/hour")
    print(f"  Chosen: alert when risk >= {alert_threshold:.2f} for {confirm * 0.5:.1f} s in a row "
          f"(training files: {cv_events['event_recall']:.0%} events caught, "
          f"{cv_events['false_alerts_per_hour']:.1f} false alerts/hour)")

    # 6. evaluate ---------------------------------------------------------------
    print("\n[6/6] Testing on unseen files ...")
    p_te = predict(x_te)
    window_metrics = compute_metrics(y_te, p_te, threshold)

    file_probs, file_rows = [], []
    labels_list = yamnet.labels or read_labels(model_path)
    test_file_probs = []
    for item in test_items:
        key = str(item.path)
        f = features[key]
        # A file counts as risky if ANY ~3 s part of it is risky.
        prob = float(predict(f).max())
        file_probs.append(prob)
        frames = frames_by[key]
        top = int(np.argmax(frames.mean(axis=0)))
        probs_frames = predict(frame_features(frames))
        test_file_probs.append((probs_frames, item))
        ev = events_from_probs(probs_frames, item, alert_threshold, confirm)
        file_rows.append({
            "file": key,
            "true_label": "violent" if item.label else "non-violent",
            "labelled_violent_seconds": round(sum(e - s for s, e in (item.intervals or [])), 1)
            if item.intervals is not None else "",
            "risk_probability": round(prob, 3),
            "predicted": "violent" if prob >= threshold else "non-violent",
            "watch_events_caught": f"{ev['detected']}/{ev['violent_events']}",
            "watch_false_alerts": ev["false_alerts"],
            "main_sound_yamnet": labels_list[top] if top < len(labels_list) else f"class {top}",
        })
    file_metrics = compute_metrics([i.label for i in test_items], np.array(file_probs), threshold)
    event_metrics = total_events(test_file_probs, alert_threshold, confirm)
    event_metrics_1s = total_events(test_file_probs, threshold, 2)   # old behaviour, for comparison

    degraded = {}
    if not args.skip_degraded:
        print("  Degraded-audio tests (noise, phone quality, distance, echo) ...")
        start = time.time()
        per_cond = {c: ([], []) for c in DEGRADED_CONDITIONS}
        for n, item in enumerate(test_items):
            try:
                y = load_audio(item.path)
            except Exception:  # noqa: BLE001
                continue
            for c in DEGRADED_CONDITIONS:
                rng = np.random.default_rng(args.seed + n)
                f = window_features(frame_scores(yamnet, degrade(y, c, rng)))
                labels, keep = window_labels(item, len(f))
                if keep.any():
                    per_cond[c][0].append(predict(f[keep]))
                    per_cond[c][1].append(labels[keep])
            progress(n, len(test_items), start, "test files")
        for c, (probs, ys) in per_cond.items():
            if probs:
                degraded[c] = compute_metrics(np.concatenate(ys), np.concatenate(probs), threshold)

    # ------------------------------------------------------------------ export
    scaler: StandardScaler = model.named_steps["standardscaler"]
    clf: LogisticRegression = model.named_steps["logisticregression"]
    head = {
        "version": 2,
        "created": datetime.now().isoformat(timespec="seconds"),
        "description": "SafeGuardAI violence detector: logistic regression on YAMNet scores "
                       "(mean + max of the last frames, then feature_transform).",
        "sample_rate": SAMPLE_RATE,
        "frame_samples": FRAME_SAMPLES,
        "hop_samples": HOP_SAMPLES,
        "frames_per_window": FRAMES_PER_WINDOW,
        "feature_transform": kind,
        # The watch alerts when risk >= threshold for confirm_results results in a row.
        "threshold": round(alert_threshold, 4),
        "confirm_results": confirm,
        "window_threshold": round(threshold, 4),
        "scaler_mean": [round(float(v), 7) for v in scaler.mean_],
        "scaler_scale": [round(float(v), 7) for v in scaler.scale_],
        "coef": [round(float(v), 7) for v in clf.coef_[0]],
        "intercept": float(clf.intercept_[0]),
        "test_metrics_window_level": window_metrics,
        "test_metrics_watch_events": event_metrics,
    }
    (assets_dir / "violence_head.json").write_text(json.dumps(head))
    shutil.copyfile(model_path, assets_dir / "yamnet.tflite")
    (assets_dir / "yamnet_labels.txt").write_text("\n".join(labels_list) + "\n")

    # ------------------------------------------------------------------ report
    all_metrics = {
        "dataset": str(args.data),
        "files": {"train": len(train_items), "test": len(test_items)},
        "windows": {"train": int(len(y_tr)), "test": int(len(y_te))},
        "model": {"pretrained": "YAMNet (TFLite, 521 AudioSet classes)",
                  "classifier": "Logistic regression", "feature_transform": kind, "C": best_c,
                  "threshold": threshold, "cross_validation_folds": n_folds},
        "cross_validation_window_level": cv_metrics,
        "test_window_level": window_metrics,
        "test_file_level": file_metrics,
        "alert_setting": {"threshold": alert_threshold, "seconds_of_risk": confirm * 0.5,
                          "max_false_alerts_per_hour_target": args.max_false_alerts},
        "cross_validation_watch_events": cv_events,
        "test_watch_events": event_metrics,
        "test_watch_events_old_1s_setting": event_metrics_1s,
        "degraded_audio_window_level": degraded,
    }
    (report_dir / "metrics.json").write_text(json.dumps(all_metrics, indent=2))
    with open(report_dir / "errors.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(file_rows[0].keys()) + ["result"])
        w.writeheader()
        for r in file_rows:
            correct = r["true_label"] == r["predicted"]
            result = "correct" if correct else (
                "FALSE POSITIVE" if r["predicted"] == "violent" else "FALSE NEGATIVE")
            w.writerow({**r, "result": result})
    save_confusion_png(window_metrics, report_dir / "confusion_matrix.png",
                       f"Test windows (threshold {threshold:.2f})")
    write_report_md(report_dir / "report.md", all_metrics)

    m, e = window_metrics, event_metrics
    print("\n================ RESULTS (unseen test audio) ================")
    print(f"  ~3 s windows:  Precision {m['precision']:.3f}   Recall {m['recall']:.3f}   "
          f"F1 {m['f1']:.3f}   Accuracy {m['accuracy']:.3f}")
    print(f"                 False positives {m['false_positives']}   False negatives {m['false_negatives']}")
    print(f"  Like the watch (alert when risk >= {alert_threshold:.2f} for {confirm * 0.5:.1f} s):")
    print(f"                 Violent events caught {e['violent_events_detected']}/{e['violent_events']}"
          f" ({e['event_recall']:.0%})   False alerts {e['false_alerts']}"
          f" in {e['calm_hours'] * 60:.0f} min of calm audio ({e['false_alerts_per_hour']:.1f}/hour)")
    o = event_metrics_1s
    print(f"  (old ~1 s setting: {o['event_recall']:.0%} caught, {o['false_alerts_per_hour']:.1f} false alerts/hour)")
    if degraded:
        print("  Degraded audio F1: " + ", ".join(f"{c} {v['f1']:.2f}" for c, v in degraded.items()))
    print(f"\n  Report:         {report_dir / 'report.md'}")
    print(f"  For the watch:  copy the folder  {assets_dir}  into  app/src/main/assets/")


def save_confusion_png(m: dict, path: Path, title: str) -> None:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return
    cm = np.array([[m["true_negatives"], m["false_positives"]],
                   [m["false_negatives"], m["true_positives"]]])
    fig, ax = plt.subplots(figsize=(4.6, 4))
    ax.imshow(cm, cmap="Blues")
    for (r, c), v in np.ndenumerate(cm):
        ax.text(c, r, str(v), ha="center", va="center", fontsize=14,
                color="white" if v > cm.max() / 2 else "black")
    ax.set_xticks([0, 1], ["Predicted\nnon-violent", "Predicted\nviolent"])
    ax.set_yticks([0, 1], ["Actual\nnon-violent", "Actual\nviolent"])
    ax.set_title(title)
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)


def write_report_md(path: Path, a: dict) -> None:
    def row(name, m):
        return (f"| {name} | {m['precision']:.3f} | {m['recall']:.3f} | {m['f1']:.3f} | "
                f"{m['accuracy']:.3f} | {m['false_positives']} | {m['false_negatives']} |")

    head = "| | Precision | Recall | F1 | Accuracy | False positives | False negatives |\n|---|---|---|---|---|---|---|"
    lines = [
        "# SafeGuardAI – audio violence detection test results",
        "",
        f"Generated {datetime.now():%d %b %Y %H:%M}. Dataset: `{a['dataset']}`",
        "",
        "**Model:** pretrained YAMNet (Google, 521 AudioSet sound classes) → mean + max of its scores "
        f"over ~3 s of audio ({a['model']['feature_transform']} features) → logistic regression "
        f"(C={a['model']['C']}) trained on the dataset. Settings chosen by "
        f"{a['model']['cross_validation_folds']}-fold cross-validation by file. "
        f"Alert threshold **{a['model']['threshold']:.2f}**.",
        "",
        f"**Data split (by file):** train {a['files']['train']} files "
        f"({a['windows']['train']} windows), test {a['files']['test']} unseen files "
        f"({a['windows']['test']} windows).",
        "",
        "## Results on unseen test audio",
        "",
        head,
        row("Cross-validation (training files)", a["cross_validation_window_level"]),
        row("~3 s windows (test)", a["test_window_level"]),
        row("Whole files (risky if any part is risky)", a["test_file_level"]),
        "",
        "### Behaving like the watch",
        "",
        "The test recordings are replayed exactly as on the watch: a risk result every 0.5 s, "
        f"and an alert when the risk stays at or above **{a['alert_setting']['threshold']:.2f}** for "
        f"**{a['alert_setting']['seconds_of_risk']:.1f} s**. This setting was chosen on the training "
        f"files only, to catch as many violent events as possible with at most "
        f"{a['alert_setting']['max_false_alerts_per_hour_target']:g} false alerts per hour.",
        "",
        "| Violent events caught | False alerts | Calm audio | False alerts / hour | Median time to alert |",
        "|---|---|---|---|---|",
        f"| {a['test_watch_events']['violent_events_detected']} / {a['test_watch_events']['violent_events']} "
        f"({a['test_watch_events']['event_recall']:.0%}) | {a['test_watch_events']['false_alerts']} | "
        f"{a['test_watch_events']['calm_hours'] * 60:.0f} min | "
        f"{a['test_watch_events']['false_alerts_per_hour']:.1f} | "
        + (f"{a['test_watch_events']['median_seconds_to_alert']:.1f} s |"
           if a['test_watch_events']['median_seconds_to_alert'] is not None else "– |"),
        "",
        f"For comparison, alerting after ~1 s at the window threshold would catch "
        f"{a['test_watch_events_old_1s_setting']['event_recall']:.0%} of events with "
        f"{a['test_watch_events_old_1s_setting']['false_alerts_per_hour']:.1f} false alerts per hour.",
        "",
        "![Confusion matrix](confusion_matrix.png)",
        "",
    ]
    if a["degraded_audio_window_level"]:
        lines += ["## Degraded audio (same test files)", "", head.replace("| |", "| Condition |", 1)]
        for c, m in a["degraded_audio_window_level"].items():
            lines.append(row(CONDITION_DESCRIPTIONS.get(c, c), m))
        lines.append("")
    lines += [
        "## How to read this",
        "",
        "- **Precision**: when the AI raises an alert, how often it is right.",
        "- **Recall**: of all real violent clips, how many the AI finds.",
        "- **False positives**: alerts with no real risk. **False negatives**: risks the AI missed.",
        "- Per-file results, including every false positive and false negative, are in `errors.csv`.",
        "",
        "## Limitations",
        "",
        "- The dataset is not domestic-violence-specific, so results do not prove real-world performance.",
        "- The model hears *sounds* (shouting, screaming, crying, impacts); it does not understand words.",
        "- The output is a potential risk indicator, not a determination that violence occurred.",
    ]
    path.write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit("\nStopped.")
