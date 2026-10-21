# Voiability — Implementation Plan

> **Status:** Implemented. M0 to M4 are done; see README.md. Model sizes and timings below are the original estimates.

A Wispr Flow–style voice dictation app for Android: speak anywhere, get clean, well-formatted text typed into any app. **Everything runs on-device.** No accounts, no servers, no network permission.

## 1. Product goals

- **Dictate anywhere:** works in any text field (messages, email, notes, browser).
- **Clean output, not raw transcripts:** removes filler words ("um", "like"), applies self-corrections ("at 5, no wait, 6" becomes "at 6"), adds punctuation, and formats lists.
- **Private by design:** the manifest has no `INTERNET` permission, so users can confirm nothing leaves the phone.
- **Easy to use:** a 60-second onboarding, one big mic button, and sensible defaults. No settings are needed to get good results.

## 2. Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language/UI | Kotlin + Jetpack Compose, Material 3 (dynamic color) | Modern standard, fast to build |
| Speech-to-text | **whisper.cpp** via JNI (NDK/CMake); models: `base.en` q5 (default), `small` q5 (high accuracy), `tiny` (low-end devices) | Best accuracy for an offline model, quantized, runs on ARM NEON |
| Voice activity detection | Silero VAD (ONNX Runtime) | Trims silence, auto-stops, speeds up STT |
| Text cleanup LLM | **llama.cpp** via JNI with a small instruct model (Qwen2.5-1.5B or Gemma-3-1B, Q4_K_M, ~1 GB) | Rewrites the transcript locally. Optional, with a rule-based fallback |
| Storage | Room (history and dictionary), DataStore (settings) | Simple and local |
| DI/async | Hilt, Coroutines/Flow | Standard |

Models are downloaded once on first run from a pinned URL with a SHA-256 check, or sideloaded. This is the only network use, and it lives in a separate opt-in "model downloader" flow. *Alternative:* bundle `base.en` in the APK through Play Asset Delivery so the app works 100% offline from install.

## 3. Architecture

```
Mic (AudioRecord 16kHz mono)
  → VAD (Silero) → audio buffer
  → STT engine (whisper.cpp)         → raw transcript
  → Cleanup pipeline
       1. Rule pass: fillers, spoken punctuation ("new line", "comma"), personal dictionary
       2. LLM pass (optional): tone/format, using app context (e.g. Gmail = email, WhatsApp = casual)
  → Output: commit text into the focused field
```

Modules: `:app` (UI), `:core:audio`, `:core:stt`, `:core:llm`, `:core:pipeline`, `:ime`, `:data`. Native code lives in `:core:stt` and `:core:llm` as separate `.so` files, loaded lazily.

**How text gets into other apps:**
1. **Primary: a custom keyboard (IME).** A minimal keyboard with a large mic button, plus a "switch back to my keyboard" key. This is the most reliable and Play-Store-safe way to type into any app.
2. **Secondary: a floating bubble** (overlay + Accessibility Service) that appears next to text fields while users keep their normal keyboard. It's off by default, and onboarding explains clearly why it needs access.
3. **Fallback:** copy to clipboard and show a toast.

## 4. Milestones

**M0: Skeleton (week 1)**
- Gradle multi-module project, CI (GitHub Actions: build, lint, unit tests), NDK/CMake setup.
- whisper.cpp builds for `arm64-v8a`, and a JNI "hello transcription" works on a WAV file.

**M1: Core dictation (weeks 2–3)**
- Record audio and run VAD, then transcribe with whisper.cpp, then show the text in an in-app scratchpad.
- Model manager: download, verify, delete, and choose a model, with automatic picks based on device RAM.
- Target: under 1.5 s from end of speech to text for a 10 s clip on a mid-range phone (base.en).

**M2: Type anywhere (weeks 4–5)**
- IME keyboard with mic, a live "listening" waveform, cancel/undo, and a switch-keyboard key.
- Push-to-talk (hold) and tap-to-toggle modes, plus haptics.
- Recording runs in a foreground service with the mic type, so it's reliable on Android 14+.

**M3: Smart cleanup (weeks 6–7)**
- Rule-based cleaner (fillers, spoken punctuation, capitalization, numbers).
- Personal dictionary (names and jargon), also passed to Whisper as an `initial_prompt` for better recognition.
- LLM rewrite pass with per-app styles (Casual, Formal, Email, Notes/bullets), streamed into the field.
- **Command mode:** select text, then say "make this shorter" or "translate to Spanish."

**M4: Polish and UX (weeks 8–9)**
- Onboarding: permissions, a test sentence, and setting up the keyboard in 3 screens.
- Searchable history with one-tap re-insert. Auto-delete after N days (default 7).
- Floating bubble mode, a Quick Settings tile, and a home-screen widget.
- Accessibility (TalkBack labels, large touch targets), dark mode, and multiple languages (multilingual Whisper model).

**M5: Performance and release (week 10)**
- Benchmark on low, mid, and high-end devices. Tune threads, quantization, and model defaults.
- Warm-load the models while the keyboard is open, and unload them under memory pressure (`onTrimMemory`).
- Battery and thermal checks, ProGuard/R8, and Play Store listing (data safety: "no data collected"). Also publish to F-Droid.

## 5. Key risks and mitigations

| Risk | Mitigation |
|---|---|
| Slow on low-end phones | Device-tier model picks, `tiny` fallback, skip the LLM pass when RAM < 6 GB |
| LLM changes meaning or hallucinates | Strict prompt ("only fix, never add"), length/diff guard that falls back to the rule-cleaned text, and one-tap "use raw" |
| Large model downloads | Resumable downloads on Wi-Fi only, clear sizes shown, optional LLM |
| Accessibility API review on Play | The IME is the main path. The bubble is clearly disclosed and opt-in |
| Mic killed in the background | Foreground service with `FOREGROUND_SERVICE_MICROPHONE` |

## 6. Definition of done (v1)

- Dictate into any app via the keyboard in 2 taps or fewer.
- Clean, punctuated text in under 2 s for typical sentences on a mid-range device (e.g. Pixel 7a).
- Works in airplane mode after setup, and the app has no `INTERNET` permission (except the optional downloader flavor).
- Word error rate within 10% of Whisper's desktop results on a small internal test set.
