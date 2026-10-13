## What this is
A test of the [Audio-based Violence Detection Dataset (VSD, Kaggle)](https://www.kaggle.com/datasets/fangfangz/audio-based-violence-detection-dataset), to check whether it is suitable for SafeGuard AI's audio risk module.

## How the labels work
`VSD.xlsx` does **not** give one label per file. Its `read_dataset` sheet lists, for each *segment file* (e.g. `angry_011`), the time ranges in seconds where violence happens. A tiny placeholder range such as 0.00–0.01 s means "no violence in this file". The files `angry_01`–`angry_21` are the uncut original recordings the segments were cut from, so the notebook skips them to avoid counting the same audio twice.

The notebook cuts every segment file into 3-second windows. A window is **violent** if at least half of it is inside a violent range, **non-violent** if none of it is, and skipped if it is only partly violent.

## Tests performed (`dataset_testing.ipynb`)
| # | Test | Output |
|---|---|---|
| 1 | Folder structure and label file contents | notebook |
| 2 | Match audio files to labels; find unlabelled audio and labels with no audio | notebook |
| 3 | Integrity: unreadable files, duplicates (SHA-256), file length vs. Excel, ranges past end of file | `results/file_check.csv` |
| 4 | Window labelling, class balance, length of violent ranges, sample rate | `results/windows.csv`, `results/stats.png` |
| 5 | Listening check of example violent / non-violent windows | notebook |
| 6 | Baseline classifier (MFCC + Random Forest, 300 windows per class, train/test split by original recording) | `results/confusion_matrix.png` |
| 7 | Robustness to background noise (20 / 10 / 0 dB SNR): recall and false-alarm rate | `results/noise_results.csv`, `results/noise_test.png` |
| 8 | Pass/fail checklist and summary | `results/summary.json` |

## How to run
1. Download the dataset from Kaggle and place it next to the notebook:
   ```
   dataset-testing/
   ├── dataset_testing.ipynb
   └── dataset/
       ├── audios_VSD/   ← .wav files (sub-folders are fine)
       └── VSD.xlsx
   ```
2. Install: `pip install pandas openpyxl numpy matplotlib librosa soundfile scikit-learn` (the first notebook cell also does this).
3. Open `dataset_testing.ipynb` and **Run All**, or run it from the command line:
   `jupyter nbconvert --to notebook --execute dataset_testing.ipynb --inplace`
   The full run takes about a minute on a laptop. Results are written to `results/`.

## Results summary
Run on 13 Oct 2026, full dataset (131 WAV files).

| Item | Value |
|---|---|
| Labelled segment files used | 109 (from 25 original recordings), 7.76 h, all 44.1 kHz stereo |
| Violent audio | 0.85 h, 317 violent ranges (0.66–141 s, mean 9.6 s) |
| 3-second windows (violent / non-violent) | 1,027 / 8,023 → **7.8 : 1 imbalance** |
| Unreadable / duplicate files | 0 / 0 |
| Label issues | `angry_206` has audio but no labels; `angry_093` is 78 s but listed as 49 s |
| Baseline recall / precision (clean) | 0.55 / 0.84 (accuracy 0.70, 122 test windows from 7 unseen recordings) |
| Recall (false-alarm rate) with noise | 20 dB: 0.86 (0.07) · 10 dB: **0.69** (0.02) · 0 dB: 0.11 (0.00) |
| Suitable for SafeGuard AI? | **Partly** – see below |

**Checklist:** ✅ all files open · ✅ no duplicates · ✅ every labelled file has audio · ❌ every audio file has labels · ❌ file lengths match Excel · ❌ classes balanced (≤ 3 : 1) · ❌ baseline catches ≥ 85% of violent windows · ❌ ≥ 70% recall at 10 dB noise

**Verdict:** The audio is clean and the time-stamped labels suit a windowed detector, but the dataset is not ready to use as-is. Before training: balance the classes, split by original recording, fix or drop `angry_093` and `angry_206`, and add household-noise augmentation. 75% of the non-violent audio comes from four recordings, so a model could learn recording differences rather than violence. VSD should be combined with other datasets and re-tested with a stronger model, such as pretrained audio embeddings. The noise results did not get steadily worse as noise increased, which shows the small test set makes them unstable, so treat them as indicative.

## Notes
- Audio files are **not** in this repo (dataset licence and size). Download from Kaggle.
- The dataset covers general violence, not domestic violence specifically; results are risk indicators only.
