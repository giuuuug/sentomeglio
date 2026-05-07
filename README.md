
<p align="center">
  <!-- Selettore Lingua -->
  <a href="#pillole-ita">
    <img src="https://img.shields.io/badge/lang-ITA-green?style=for-the-badge" />
  </a>
  <a href="#pillole-eng">
    <img src="https://img.shields.io/badge/lang-ENG-blue?style=for-the-badge" />
  </a>
  <br />
</p>


# <img src="sentomeglio_logo.svg" width="80" height="80" alt="Sentomeglio Logo" style="display: inline-block; vertical-align: middle; margin-right: 20px;"/> Sentomeglio

<a id="italiano"></a>

<details open>
<summary><b>📋 Indice</b></summary>

- [Cos'è Sentomeglio?](#cosa-è-sentomeglio)
- [Le due schermate](#le-due-schermate)
- [Per chi è e come si usa](#per-chi-è-e-come-si-usa)
- [Come leggere la schermata Dev](#come-leggere-la-schermata-dev)
- [Requisiti di sistema](#requisiti-di-sistema)
- [Setup e installazione](#setup-e-installazione)
- [Script di build e run](#script-di-build-e-run)

</details>

### Cos'è Sentomeglio?

Sentomeglio è un'app Android che utilizza l'**intelligenza artificiale** per migliorare la qualità dell'audio in tempo reale. Sfrutta un modello di deep learning (ONNX) per ridurre il rumore di fondo e migliorare la chiarezza della voce durante le chiamate, le registrazioni o qualsiasi uso che richieda un audio pulito.

L'app funziona direttamente sul dispositivo, senza necessità di connessione internet o cloud.

### Le due schermate

#### 1️⃣ **Schermata Daily** (Uso quotidiano)
La versione semplificata per l'utente finale:
- **Pulsante REC grande**: tocca per avviare/fermare la registrazione
- **Selezione dispositivi audio**: scegli il microfono di input e l'output (speaker/cuffie)
- **Timer visibile**: mostra il tempo di registrazione in tempo reale
- **Nessun parametro da configurare**: tutto è automatico

#### 2️⃣ **Schermata Dev** (Sviluppatori e esperti)
La versione avanzata per monitorare e ottimizzare le performance:
- **Model selector**: scegli quale modello ONNX usare
- **Parametri STFT**: configura n_fft, hop_length, win_length per esperimenti
- **Metriche di performance**:
  - **Latency**: mostra HW (hardware), DSP (processing), Inference (rete neurale)
  - **RTF (Real-Time Factor)**: rapporto tra il tempo di elaborazione e il tempo del segnale
  - **HW In/Out**: latenza separata di input e output
  - **Buffer/Burst**: dimensioni dei buffer audio
  - **XRun count**: conta i glitch audio (idealmente 0)
  - **Sample rate**: frequenza di campionamento
  - **Sharing mode**: modalità esclusiva o condivisa
- **Spettrogrammi visivi**: vedi lo spettrogramma dell'audio noisy (input) e denoised (output)
- **Console di log**: traccia timestampata di tutti gli eventi

### Per chi è e come si usa

| **Caso d'uso** | **Schermata** | **Procedura** |
|---|---|---|
| **Utente finale** | Daily | 1. Seleziona input/output<br>2. Tocca REC<br>3. Parla (enhancement automatico) |
| **Developer** | Dev | 1. Seleziona modello<br>2. Configura parametri STFT (opzionale)<br>3. Tocca REC<br>4. Monitora metriche e log in tempo reale |
| **Test performance** | Dev | Configura n_fft, hop_length, win_length<br>Osserva RTF e latency<br>Leggi il log per errori |

### Come leggere la schermata Dev

**Esempio di lettura durante una sessione attiva:**

```
─── Modello: DNS4.onnx
Flash : 3.42 MB
RAM   : ~6.84 MB
───────────────────────────────────────────────────────
Avvio audio
STFT: n_fft=512  hop=128  win=320
Input : Built-in Microphone
Output: Speaker
────────────────────────────────────────────────────────
Engine avviato
HW: 45.3 ms  |  DSP: 12.45 ms  |  Infer: 8.92 ms
RTF: 0.845
HW In: 22.1 ms  |  HW Out: 23.2 ms
Buffer: 256 fr (16.0 ms)  |  Burst: 128 fr (8.0 ms)
XRun: 0  |  Rate: 16000 Hz  |  Exclusive
```


### Requisiti di sistema

✅ **Sistema operativo**: Android 14+ (API level 34+)

### Setup e installazione

#### ✨ Nessuna configurazione necessaria!

**Permessi richiesti (concessi al primo avvio):**
- Microfono
- Notifiche (per il servizio di registrazione in background)

### Script di build e run

#### 📋 Prerequisiti
- Android SDK 36 (compileSdk)
- Android NDK (per la compilazione C++)
- Gradle 8.0+
- Java 11+

#### 🔨 Build APK

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (con ProGuard)
./gradlew assembleRelease

# Output: android/app/build/outputs/apk/debug/app-debug.apk
```

#### 🚀 Installare e lanciare su dispositivo/emulatore

```bash
# Installa e lancia direttamente
./gradlew installDebug
adb shell am start -n com.sentomeglio.app/.DailyScreenActivity

# Oppure, build + install in un comando
./gradlew runDebug
```

#### 📱 Se il dispositivo non è riconosciuto

```bash
# Lista dispositivi connessi
adb devices

# Se nessun dispositivo appare:
adb kill-server
adb start-server
adb devices
```

#### 🐛 Run della schermata Dev

```bash
# Per testare la schermata Dev, attiva prima la dev mode
adb shell am start -n com.sentomeglio.app/.DailyScreenActivity
# Poi, da app, attiva il toggle dev mode (se presente)
# oppure esegui direttamente:
adb shell am start -n com.sentomeglio.app/.DevScreenActivity
```

#### 📊 Debug e log

```bash
# Visualizza i log in tempo reale
adb logcat | grep sentomeglio

# Salva i log in file
adb logcat > sentomeglio_logs.txt

# Cancella i log precedenti
adb logcat -c
```

#### 🔧 Build customizzati

```bash
# Build solo ARM64 (più veloce)
./gradlew assembleDebug -P android.bundle.enableUncompressNativeLibraries=false

# Build con debug symbols
./gradlew assembleDebug

# Clean e rebuild completo
./gradlew clean assembleDebug
```

---

<br>
<br>
<br>
<br>
<br>

# <img src="sentomeglio_logo.svg" width="80" height="80" alt="Sentomeglio Logo" style="display: inline-block; vertical-align: middle; margin-right: 20px;"/> Sentomeglio

<a id="english"></a>

<details open>
<summary><b>📋 Table of Contents</b></summary>

- [What is Sentomeglio?](#what-is-sentomeglio)
- [The two screens](#the-two-screens)
- [For whom and how to use](#for-whom-and-how-to-use)
- [How to read the Dev screen](#how-to-read-the-dev-screen)
- [System requirements](#system-requirements)
- [Setup and installation](#setup-and-installation)
- [Build and run scripts](#build-and-run-scripts)

</details>

### What is Sentomeglio?

Sentomeglio is an Android app that uses **artificial intelligence** to improve audio quality in real-time. It leverages a deep learning model (ONNX) to reduce background noise and enhance voice clarity during calls, recordings, or any use case requiring clean audio.

The app runs directly on your device with no internet connection or cloud service needed.

### The two screens

#### 1️⃣ **Daily Screen** (Everyday use)
The simplified version for end users:
- **Large REC button**: tap to start/stop recording
- **Audio device selection**: choose your microphone input and output (speaker/headphones)
- **Visible timer**: displays recording time in real-time
- **No configuration needed**: everything is automatic

#### 2️⃣ **Dev Screen** (Developers and advanced users)
The advanced version for monitoring and optimizing performance:
- **Model selector**: choose which ONNX model to use
- **STFT parameters**: configure n_fft, hop_length, win_length for experiments
- **Performance metrics**:
  - **Latency**: shows HW (hardware), DSP (processing), Inference (neural network)
  - **RTF (Real-Time Factor)**: ratio of processing time to signal time
  - **HW In/Out**: separate input and output latency
  - **Buffer/Burst**: audio buffer sizes
  - **XRun count**: counts audio glitches (ideally 0)
  - **Sample rate**: sampling frequency
  - **Sharing mode**: exclusive or shared mode
- **Visual spectrograms**: see the spectrogram of the noisy input audio and denoised output
- **Log console**: timestamped trace of all events

### For whom and how to use

| **Use case** | **Screen** | **Steps** |
|---|---|---|
| **End user** | Daily | 1. Select input/output<br>2. Tap REC<br>3. Speak (automatic enhancement) |
| **Developer** | Dev | 1. Select model<br>2. Configure STFT parameters (optional)<br>3. Tap REC<br>4. Monitor metrics and logs in real-time |
| **Performance testing** | Dev | Configure n_fft, hop_length, win_length<br>Observe RTF and latency<br>Read log for errors |

### How to read the Dev screen

**Example of reading during an active session:**

```
─── Model: DNS4.onnx
Flash : 3.42 MB
RAM   : ~6.84 MB
────────────────────────────────────────────────────────
Starting audio
STFT: n_fft=512  hop=128  win=320
Input : Built-in Microphone
Output: Speaker
────────────────────────────────────────────────────────
Engine started
HW: 45.3 ms  |  DSP: 12.45 ms  |  Infer: 8.92 ms
RTF: 0.845
HW In: 22.1 ms  |  HW Out: 23.2 ms
Buffer: 256 fr (16.0 ms)  |  Burst: 128 fr (8.0 ms)
XRun: 0  |  Rate: 16000 Hz  |  Exclusive
```


### System requirements

✅ **Operating system**: Android 14+ (API level 34+)

### Setup and installation

#### ✨ No configuration needed!

**Permissions requested (granted on first launch):**
- Microphone
- Notifications (for background recording service)

### Build and run scripts

#### 📋 Prerequisites
- Android SDK 36 (compileSdk)
- Android NDK (for C++ compilation)
- Gradle 8.0+
- Java 11+

#### 🔨 Build APK

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (with ProGuard)
./gradlew assembleRelease

# Output: android/app/build/outputs/apk/debug/app-debug.apk
```

#### 🚀 Install and launch on device/emulator

```bash
# Install and launch directly
./gradlew installDebug
adb shell am start -n com.sentomeglio.app/.DailyScreenActivity

# Or, build + install in one command
./gradlew runDebug
```

#### 📱 If your device is not recognized

```bash
# List connected devices
adb devices

# If no device appears:
adb kill-server
adb start-server
adb devices
```

#### 🐛 Run the Dev screen

```bash
# To test the Dev screen, enable dev mode first from the Daily screen
adb shell am start -n com.sentomeglio.app/.DailyScreenActivity
# Then, from the app, enable the dev mode toggle (if available)
# Or run directly:
adb shell am start -n com.sentomeglio.app/.DevScreenActivity
```

#### 📊 Debug and logs

```bash
# View logs in real-time
adb logcat | grep sentomeglio

# Save logs to file
adb logcat > sentomeglio_logs.txt

# Clear previous logs
adb logcat -c
```

#### 🔧 Custom builds

```bash
# Build ARM64 only (faster)
./gradlew assembleDebug -P android.bundle.enableUncompressNativeLibraries=false

# Build with debug symbols
./gradlew assembleDebug

# Clean and full rebuild
./gradlew clean assembleDebug
```

---

<div align="center">

Made with ❤️ for clean audio

</div>
