# SafeGuardAI

**On-device audio evidence capture and AI danger detection for Wear OS smartwatches**
COMP6002 Computer Science Project, Curtin University, Group 13 (2026)

> **Please read first.** SafeGuardAI is a university **prototype**. It has only been tested on a Wear OS **emulator**, it raises many false alerts, and its accuracy drops sharply in noise. It is **not** a safety product and must not be relied on to protect anyone. If you or someone else is in danger, contact your local emergency services.

---

## What it does

A watch app that listens through the microphone and does two separate jobs:

1. **Private recording on a secret phrase.** The wearer says a chosen 2 to 4 word phrase and the watch starts recording. Saying it again, or tapping Stop, ends the recording. These recordings stay on the watch and are never sent anywhere.
2. **AI danger detection and alert.** Google's pretrained YAMNet sound model (trained on [AudioSet](https://research.google/pubs/pub45857/)) plus a small classifier we trained on the Kaggle Audio-based Violence Detection (VSD) dataset scores the sound every 0.5 seconds. If the risk stays at or above 0.70 for 1 second, the watch records a 60-second evidence clip, shows a danger banner and sends an SMS to a saved emergency contact. A second SMS with a link to the audio follows once the clip is uploaded.

Everything is processed on the watch. Until the phrase is heard, audio only exists in a 5-second in-memory buffer that is constantly overwritten. Only the AI-triggered path ever reaches the network.

## Results in one table

All numbers come from our technical report. Window-level scores are on a held-out 20% of recordings (split by file). Event-level numbers come from replaying test audio exactly as the watch would process it.

| Measure | Result |
|---|---|
| Cross-validation F1 (grouped by file) | 0.62 |
| Test precision / recall / F1 (3-second windows) | 0.606 / 0.532 / 0.566 |
| Violent events detected (alert rule: risk of at least 0.70 for 1.0 s) | 59 of 65 (91%) |
| False alerts on the dataset's calm sections | about 84.5 per hour (mostly loud arguments, the hardest case) |
| F1 on clean audio vs noise at 0 dB SNR | 0.57 vs 0.11 |

**What this means.** The detector catches most violent events, but the false-alert rate is far too high for real use, and noise breaks it. We have not measured false alerts on ordinary household audio.

## Repository layout

```
SafeGuardAIWear/                 Wear OS app (Kotlin, Jetpack Compose for Wear OS)
  app/src/main/java/com/example/safeguardaiwear/
    presentation/MainActivity.kt   screens, navigation, permissions, playback
    service/KeywordListenerService.kt   foreground mic service, phrase detection, AI loop, recording, alerts
    service/AacWriter.kt           PCM to AAC (.m4a) encoder
    speech/VoskModelManager.kt     loads the offline speech model
    ai/RiskDetector.kt             YAMNet inference, features, classifier, loudness gate
    alert/AlertSender.kt           Twilio SMS and phone-number handling
    alert/EvidenceUploader.kt      Cloudinary audio upload
    alert/Secrets.kt               service keys (empty in this repository)
    data/SafeGuardPrefs.kt         consent, phrase, contact, settings, alert log
  app/src/main/assets/
    model-en-us/                   Vosk small English model
    ai/                            yamnet.tflite, yamnet_labels.txt, violence_head.json
safeguard-ai-model/              Python model training kit
  train_and_evaluate.py            labelling, features, cross-validation, test, watch simulation
  check_audio.py                   score any audio file the way the watch does
  safeguard_ai/                    model loading, audio framing and features, dataset labelling
  output/report/                   generated report, metrics, confusion matrix, errors
dataset-testing/                 notebook that checks the VSD dataset (see below)
UI Design/                       app and watch design files
SafeGuard_AI_FRS.xlsx            requirements workbook (the full vision, see Scope)
SafeGuard AI Testing Document.xlsx   test document
```

## Run it

### 1. The watch app (Android Studio)

Requirements: Android Studio with a Wear OS emulator image. The app uses `minSdk 30` and `targetSdk 37`. We tested on the **Wear OS Small Round** emulator (Android 17). We have **not** tested on a physical watch.

1. Open the `SafeGuardAIWear/` folder in Android Studio and let Gradle sync.
2. Create or start a Wear OS Small Round emulator and run the `app` configuration.
3. On first launch, follow the screens: **Consent** (allow microphone and notifications), choose a **secret phrase**, then add an **emergency contact**.
4. Turn protection on from the Home screen. Say your phrase to start and stop a private recording.

The app works **without any keys**. If no service keys are set, the SMS and upload steps run in a **simulated mode** that shows what would be sent without sending anything.

### 2. Real SMS and audio links (optional)

1. Open `alert/Secrets.kt` and fill in the Twilio and Cloudinary values it lists.
2. Use the **Send test SMS** button on the Emergency Contact screen to check the path.
3. On a Twilio **trial** account, only verified numbers can receive messages, the text must be one of Twilio's templates (set `TWILIO_TRIAL_TEMPLATE = "sms_account_alerts"`), and the audio link cannot be included. Set the template value to an empty string after upgrading.

**Never commit real keys.** Keep `Secrets.kt` empty in anything you push, and replace any keys that were ever exposed.

### 3. Retrain or test the model (optional)

1. Download the [Audio-based Violence Detection dataset from Kaggle](https://www.kaggle.com/datasets/fangfangz/audio-based-violence-detection-dataset). The audio is **not** stored in this repository. Check the dataset's licence on Kaggle before using it.
2. Install the Python packages used for training: numpy, scikit-learn, librosa, pandas and ai-edge-litert.
3. From `safeguard-ai-model/`, run `python train_and_evaluate.py`. **[TODO: add the exact arguments and the dataset folder path.]**
4. To score any audio file, including a clip copied from the watch, run `check_audio.py` the same way. **[TODO: add the exact command.]**
5. Copy the files from `output/android_assets/ai/` into the app's `assets/ai/` folder.

### 4. Check the dataset

`dataset-testing/` holds a notebook that tests whether the VSD dataset is suitable (file integrity, label matching, class balance, a baseline classifier and noise robustness). See `dataset-testing/README.md` for how to run it. Its main finding is that the dataset is usable but imbalanced, and that a few recordings supply most of the calm audio.

## Scope and known limitations

SafeGuardAI is a **narrower prototype than our original proposal**. The requirements workbook (`SafeGuard_AI_FRS.xlsx`) describes the full vision: a disguised phone app, an encrypted evidence vault, an audio-enhancement layer and more. This repository implements the watch app, the AI detector and SMS alerting described above.

- **Emulator only.** Battery use, microphone quality and background limits on real watches are untested.
- **Too many false alerts.** About 84.5 per hour on difficult non-violent audio would cause alarm fatigue.
- **Noise.** F1 falls from 0.57 to 0.11 at 0 dB signal-to-noise ratio. Distance from the microphone also hurts.
- **Sounds, not words.** The model hears shouting, screaming, crying and impacts. It does not understand speech, so calm verbal threats are missed.
- **Dataset fit.** VSD is general violence data, not domestic violence, and was not recorded on a wrist.
- **Twilio trial.** Alerts use a generic template without the audio link, and only verified numbers receive them.
- **Prototype security.** Keys live in the app, the Cloudinary preset is unsigned (anyone with a link can open the clip), and stored clips have a SHA-256 fingerprint but no encryption of our own. A real system would keep keys on a backend, use signed expiring links and encrypt stored audio.

## Privacy and responsible use

- Audio is processed **on the device**. Phrase recordings never leave the watch.
- The app asks for **consent**, shows a persistent notification while the microphone is on, and lets the wearer withdraw consent at any time.
- Contacts access is requested only when picking an emergency contact.
- The AI output is a **risk indicator, not an accusation**. A false alert can cause alarm, and a missed event can give false reassurance.
- Recording laws differ between places. Only record people who know and agree.
- Do not connect this prototype to real emergency services.

## Team (Group 13)

Habiba Rashid Lamiya, Fahima Akter Jinia, Abhishek Das, Md. Tasin Hossain Toha and Tajkamal Singh. The work was split into the watch app and listening service, emergency alerting and testing, and AI danger detection, with background study, design and the project's idea and management shared across the team.

## Licence

No open-source licence has been chosen for this repository yet. Third-party components (YAMNet, Vosk, TensorFlow Lite, Twilio, Cloudinary and the dataset) keep their own licences and terms.

## Documents

Our technical report and final report describe the design, evaluation and limitations in full. **[TODO: add links or file names if you upload them.]**
