"""Finds the audio files in the Kaggle dataset and works out their labels.

Labels come from folder names (e.g. "violence" / "non-violence", "1" / "0"),
or from a CSV file if you pass --labels-csv.
"""
from __future__ import annotations

import csv
from dataclasses import dataclass
from pathlib import Path

from .audio import AUDIO_EXTENSIONS

VIOLENT, SAFE = 1, 0

_SAFE_NAMES = {"0", "nonviolence", "nonviolent", "noviolence", "normal", "neutral",
               "safe", "negative", "nv", "nonviol", "noviolent", "peaceful", "calm"}
_VIOLENT_NAMES = {"1", "violence", "violent", "positive", "abuse", "abusive", "fight",
                  "fighting", "aggression", "aggressive", "v", "viol"}


def label_from_text(text: str) -> int | None:
    t = str(text).strip().lower().replace("-", "").replace("_", "").replace(" ", "")
    if not t:
        return None
    if t in _SAFE_NAMES or t.startswith("non") or t.startswith("noviol"):
        return SAFE
    if t in _VIOLENT_NAMES or "viol" in t or "abus" in t or "aggress" in t:
        return VIOLENT
    if t in {"true", "yes"}:
        return VIOLENT
    if t in {"false", "no"}:
        return SAFE
    return None


def split_from_parts(parts: list[str]) -> str | None:
    for p in parts:
        p = p.lower()
        if p in {"train", "training", "trainset", "train_set"}:
            return "train"
        if p in {"test", "testing", "testset", "test_set"}:
            return "test"
        if p in {"val", "valid", "validation", "dev"}:
            return "val"
    return None


@dataclass
class Item:
    path: Path
    label: int             # file level: 1 = contains violence
    split: str | None      # "train" / "val" / "test" if the dataset defines it
    # Time ranges (seconds) that are violent. None = the whole file has one label.
    intervals: list[tuple[float, float]] | None = None


def load_vsd(root: Path, sheet: Path) -> list[Item]:
    """
    Kaggle "Audio-based Violence Detection" (VSD) dataset: one spreadsheet row per
    violent time range, e.g.  angry_011 | Violence_start 21 | Violence_end 38.
    Blank name cells mean "same file as the row above". Ranges shorter than
    0.5 s (e.g. 0 – 0.02) mean "no violence in this file".
    """
    import pandas as pd

    sheet = Path(sheet)
    df = pd.read_excel(sheet) if sheet.suffix.lower() in {".xlsx", ".xls"} else pd.read_csv(sheet)
    cols = {c.lower().strip(): c for c in df.columns}
    seg_col = cols.get("file_segment_name")
    start_col, end_col = cols.get("violence_start"), cols.get("violence_end")
    if not (seg_col and start_col and end_col):
        raise SystemExit(f"{sheet.name}: expected columns File_segment_name, Violence_start, "
                         f"Violence_end – found {list(df.columns)}")
    df[seg_col] = df[seg_col].ffill()

    audio = {p.stem: p for p in Path(root).rglob("*")
             if p.suffix.lower() in AUDIO_EXTENSIONS and not p.name.startswith(".")}

    ranges: dict[str, list[tuple[float, float]]] = {}
    for _, row in df.iterrows():
        name = str(row[seg_col]).strip()
        if not name or name == "nan":
            continue
        name = Path(name).stem
        ranges.setdefault(name, [])
        try:
            start, end = float(row[start_col]), float(row[end_col])
        except (TypeError, ValueError):
            continue
        if end - start >= 0.5:
            ranges[name].append((start, end))

    items, missing = [], []
    for name, spans in sorted(ranges.items()):
        path = audio.get(name)
        if path is None:
            missing.append(name)
            continue
        items.append(Item(path, VIOLENT if spans else SAFE, None, spans))

    # Files not in the sheet: "noviolence…" files are calm; others are skipped.
    listed = set(ranges)
    skipped = []
    for stem, path in sorted(audio.items()):
        if stem in listed:
            continue
        if label_from_text(stem) == SAFE or stem.lower().startswith("noviol"):
            items.append(Item(path, SAFE, None, []))
        else:
            skipped.append(path.name)

    n_v = sum(i.label == VIOLENT for i in items)
    total_violent = sum(e - s for i in items for s, e in (i.intervals or []))
    print(f"VSD sheet: {len(items)} files ({n_v} with violent parts, {len(items) - n_v} calm), "
          f"{total_violent / 60:.1f} min of labelled violence")
    if missing:
        print(f"  {len(missing)} names in the sheet have no audio file, e.g. {missing[:3]}")
    if skipped:
        print(f"  Skipped {len(skipped)} audio files that are not in the sheet, e.g. {skipped[:3]}")
    return items


def _from_folders(root: Path) -> tuple[list[Item], list[Path]]:
    items, unlabelled = [], []
    for path in sorted(root.rglob("*")):
        if path.suffix.lower() not in AUDIO_EXTENSIONS or path.name.startswith("."):
            continue
        dirs = list(path.relative_to(root).parent.parts)
        label = None
        for part in reversed(dirs):          # deepest folder first
            label = label_from_text(part)
            if label is not None:
                break
        if label is None:
            label = label_from_text(path.stem)
        if label is None:
            unlabelled.append(path)
        else:
            items.append(Item(path, label, split_from_parts(dirs)))
    return items, unlabelled


def _from_csv(root: Path, csv_path: Path) -> tuple[list[Item], list[Path]]:
    audio = {p.name: p for p in root.rglob("*") if p.suffix.lower() in AUDIO_EXTENSIONS}
    stems = {p.stem: p for p in audio.values()}
    with open(csv_path, newline="", encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        raise SystemExit(f"{csv_path} is empty")
    cols = {c.lower(): c for c in rows[0].keys()}
    file_col = next((cols[c] for c in ("file", "filename", "file_name", "path", "name", "audio",
                                       "audio_file", "clip", "id") if c in cols), None)
    label_col = next((cols[c] for c in ("label", "labels", "class", "target", "violence",
                                        "category", "y") if c in cols), None)
    if not file_col or not label_col:
        raise SystemExit(f"Could not find file/label columns in {csv_path}. Columns: {list(rows[0])}")
    split_col = next((cols[c] for c in ("split", "set", "subset", "fold") if c in cols), None)
    items, missing = [], []
    for r in rows:
        name = Path(str(r[file_col])).name
        path = audio.get(name) or stems.get(Path(name).stem)
        label = label_from_text(r[label_col])
        if path is None or label is None:
            missing.append(Path(name))
            continue
        split = split_from_parts([str(r[split_col])]) if split_col else None
        items.append(Item(path, label, split))
    return items, missing


def find_dataset(root: Path, labels_csv: Path | None = None) -> list[Item]:
    root = Path(root)
    if not root.exists():
        raise SystemExit(f"Dataset folder not found: {root}")
    if labels_csv:
        items, skipped = _from_csv(root, Path(labels_csv))
    else:
        items, skipped = _from_folders(root)

    if skipped:
        print(f"Note: {len(skipped)} audio files had no recognisable label and were skipped, e.g.:")
        for p in skipped[:5]:
            print("   ", p)
    n_v = sum(i.label == VIOLENT for i in items)
    n_s = sum(i.label == SAFE for i in items)
    print(f"Found {len(items)} labelled audio files: {n_v} violent, {n_s} non-violent")
    if n_v == 0 or n_s == 0:
        print("\nFolders found in the dataset:")
        for d in sorted({p.parent for p in root.rglob('*') if p.is_file()})[:20]:
            print("   ", d.relative_to(root))
        raise SystemExit(
            "\nNeed both violent and non-violent examples. If the labels are in a CSV file, "
            "run again with --labels-csv path/to/file.csv"
        )
    return items
