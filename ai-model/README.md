# SafeGuardAI – audio danger detection (train & test on your Mac)

**Pretrained model:** Google YAMNet (521 everyday sound classes: speech, shouting,
screaming, crying, slap, glass breaking …).
**Trained on top:** a small classifier (logistic regression) that learns from the
Kaggle *Audio-based Violence Detection* dataset which YAMNet sound patterns mean
"violent" vs "non-violent". The same files then run on the watch.

```
microphone ─► YAMNet (every 0.5 s) ─► last ~3 s of scores ─► classifier ─► risk 0–100 %
                                                                     │
                                         risk ≥ threshold for ~1 s ──┴─► record + SMS alert
```

## 1. One-time setup (Terminal)

```bash
cd ~/Downloads/safeguard-ai-model        # wherever you unzipped this folder
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

If `ai-edge-litert` fails to install, run `pip install tensorflow` instead (either works).

## 2. Get the data and the model

1. Download the Kaggle dataset (Download button, top right) and unzip it, e.g. to
   `~/Downloads/audio-violence-dataset`.
2. YAMNet downloads automatically the first time. If that fails, download
   `lite-model_yamnet_classification_tflite_1.tflite` (link is printed) and save it as
   `models/yamnet.tflite`.

## 3. Train and test

```bash
python train_and_evaluate.py --data ~/Downloads/audio-violence-dataset
```

Quick trial run first (100 files per class, ~2 min):

```bash
python train_and_evaluate.py --data ~/Downloads/audio-violence-dataset --max-files 100
```

Fewer false alerts (at least 85 % of alerts correct, may miss more):

```bash
python train_and_evaluate.py --data ~/Downloads/audio-violence-dataset --min-precision 0.85
```

**Kaggle VSD dataset:** labels come from `VSD.xlsx` (violent time ranges inside each
recording) – it is found automatically inside `--data`. Every ~3 s window is labelled
violent if it overlaps a violent range by ≥ 1 s, calm if it doesn't overlap at all,
and skipped if it only touches one (unclear). Files not listed in the sheet are skipped,
except the `noviolence…` files, which are calm.

Other datasets: labels from folder names (`violence` / `non-violence`, `1` / `0`, …),
or a CSV with `--labels-csv path/to/labels.csv`.

### Results (for your testing report)

`output/report/`
- `report.md` – precision, recall, F1, accuracy, false positives / negatives,
  clean **and** degraded audio (noise, phone quality, far away, echo)
- `confusion_matrix.png`
- `errors.csv` – every test file, with each false positive / false negative marked
- `metrics.json` – all numbers

## 4. Check any audio file (same maths as the watch)

```bash
python check_audio.py some_clip.wav
python check_audio.py ~/Desktop/risk_1730200000000.m4a
```

## 5. Put the model on the watch

Copy the folder **`output/android_assets/ai`** into the Android project at
**`app/src/main/assets/`**, so you get:

```
app/src/main/assets/
├── ai/
│   ├── yamnet.tflite
│   ├── yamnet_labels.txt
│   └── violence_head.json
└── model-en-us/        (speech model, already there)
```

## Limitations (say these in the report)

- The dataset is not domestic-violence-specific.
- The model hears **sounds** (shouting, screaming, crying, impacts) – it does not
  understand words, so calm threats will not be detected.
- Output is a *potential risk indicator*, not proof that violence happened.
