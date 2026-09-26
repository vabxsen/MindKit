# MindKit

An offline-first AI toolkit for Android. Nine tools — ask, summarize, rewrite, proofread,
extract text, translate, image analysis, transcription and developer utilities — that run
on the device, with no account, no backend and no API key.

Kotlin, Jetpack Compose, Material 3. Single Gradle module, single activity.

---

## Contents

- [What it does](#what-it-does)
- [Privacy model](#privacy-model)
- [Supported Android versions](#supported-android-versions)
- [Gemini Nano availability](#gemini-nano-availability)
- [How capability detection works](#how-capability-detection-works)
- [Architecture](#architecture)
- [Tech stack](#tech-stack)
- [Build instructions](#build-instructions)
- [Testing](#testing)
- [Screenshots](#screenshots)
- [Known limitations](#known-limitations)
- [Roadmap](#roadmap)
- [License](#license)

---

## What it does

| Tool | Engine | Works without Gemini Nano |
| --- | --- | --- |
| **Ask AI** | ML Kit GenAI Prompt (Gemini Nano) | No |
| **Summarize** | ML Kit GenAI Summarization | No |
| **Rewrite** | ML Kit GenAI Rewriting | No |
| **Proofread** | ML Kit GenAI Proofreading | No |
| **Extract Text** | ML Kit Text Recognition v2 (bundled) | **Yes** |
| **Translate** | ML Kit on-device Translation | **Yes** |
| **Image AI** | ML Kit GenAI Image Description + multimodal Prompt | No |
| **Transcribe** | ML Kit GenAI Speech Recognition, or the platform on-device recognizer | **Partly** |
| **Developer** | Deterministic Kotlin, plus Gemini Nano for the two explain tools | **Mostly** |

Tools that a device cannot run are **shown disabled with an explanation**, not hidden. A
phone with no Gemini Nano still has a working OCR tool, a working offline translator, and
eight working developer utilities.

Other things the app does:

- **Share sheet integration.** Share text, an image or an audio file into MindKit and you
  land on an action screen that offers only what can actually be done with that content —
  not the dashboard.
- **Cross-tool handoff.** Extract text from a photo, then send it straight to Summarize,
  Translate, Rewrite or Ask without copying and pasting.
- **Local history.** Every result can be saved to an on-device Room database, searchable
  and filterable by type. History can be turned off entirely, in which case nothing is
  written at all.
- **Streaming output** for Ask AI and image questions, with a stop control.
- **Model management.** Translation language packs can be downloaded and deleted from
  Settings → Models.
- **Preference persistence.** Settings, translation language selections, onboarding
  completion and confirmed data resets share an app-owned ordered queue. Accepted
  writes survive leaving a screen, and older selections cannot overtake a later
  reset. This queue is not durable across process death. Clearing history and
  preferences is not one cross-database transaction; storage failures remain retryable.

---

## Privacy model

What the app does:

- On-device features run on the device. Text, images and audio you feed to a tool are
  processed by ML Kit or Gemini Nano locally.
- No account, no sign-in, no user identifier.
- No analytics SDK, no crash reporting SDK, no advertising SDK, no Firebase services.
- No backend of any kind. There is no server to talk to.
- History is a local SQLite database, excluded from cloud backup and device transfer
  (see `app/src/main/res/xml/data_extraction_rules.xml`).
- Content leaves the app only when *you* tap Share, which hands it to the system share
  sheet and to whichever app you then choose.

Permissions, and why each one exists:

| Permission | Why |
| --- | --- |
| `INTERNET` | Google Play services and ML Kit download on-device models you ask for — translation language packs and Gemini Nano feature models. The app itself uploads nothing. Once a model is present, the feature works with no connection. |
| `RECORD_AUDIO` | Requested at the moment you tap record on the Transcribe screen, never on screen entry. Audio is transcribed on-device and is not written to storage or transmitted. |

Deliberately **not** requested: `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`,
`CAMERA`, `READ_SMS`, `READ_CALL_LOG`, location, or any accessibility service. Images and
audio files are selected through the Android photo picker and the Storage Access
Framework, which grant access to exactly the item you pick.

### What the app does not claim

- **The models are not ours.** Gemini Nano, AICore and the ML Kit models are Android and
  Google Play services components. This app calls them; it does not control how they
  behave, and "Clear all local data" deliberately does **not** delete them, because they
  are installed system-wide and other apps may depend on them.
- **Transcription is only offline when it can be.** The app uses ML Kit's on-device
  recognizer, or `SpeechRecognizer.createOnDeviceSpeechRecognizer`. It deliberately does
  **not** fall back to the general platform recognizer even with `EXTRA_PREFER_OFFLINE`,
  because that flag is a preference rather than a guarantee. On a device with neither, the
  Transcribe tool reports unsupported rather than quietly streaming your voice to a server.
- **ML Kit pulls in `firebase-components`.** That is an internal dependency-injection
  library that shares a package name with Firebase. No Firebase product, project or
  `google-services.json` is used, and no Firebase service is contacted.

---

## Supported Android versions

| | |
| --- | --- |
| `minSdk` | 26 (Android 8.0) |
| `targetSdk` | 37 |
| `compileSdk` | 37 |
| Java/Kotlin target | 17 |

minSdk 26 is what the GenAI APIs require, and it is also what makes `java.time` and
`java.util.Base64` available to the developer tools without desugaring.

---

## Gemini Nano availability

**Gemini Nano is not available on most Android devices, and this app never pretends
otherwise.** Whether a given GenAI feature works depends on all of the following at once:

- the **device** — only some hardware ships AICore at all;
- the **system image** — AICore is updated with the platform and through Play services;
- the **AICore version** installed on that device;
- the **specific feature** — Summarization, Rewriting, Proofreading, Image Description,
  Prompt and Speech Recognition are separate features with separate availability;
- whether that **feature's model has been downloaded** to the device yet;
- the **device language** — see below;
- the **model version**, which can change what a feature returns between releases.

There is no combination of these that the app can predict ahead of time, which is why it
asks at runtime, every time.

### Language support is narrower than you would expect

Read directly from the shipped ML Kit artifacts:

| Feature | Languages the API actually supports |
| --- | --- |
| Summarization | English, Japanese, Korean |
| Rewriting | English, Japanese, Korean, German, French, Italian, Spanish |
| Proofreading | English, Japanese, Korean, German, French, Italian, Spanish |

The app maps the device locale onto these and reports **unsupported** when the device
language is not in the list, rather than sending text to a model that cannot handle it.
See `ai/gemini/GenAiLanguages.kt`.

### Summarization produces bullet points, not "lengths"

The summarization API's only output control is the *number of bullet points*:
`ONE_BULLET`, `TWO_BULLETS`, `THREE_BULLETS`. The UI's Short / Medium / Detailed options
map onto those three values, and the screen says so in as many words rather than implying
a prose-length control the model does not have.

---

## How capability detection works

`DeviceAiCapabilityManager` answers "what can this device do?" and everything else — the
Home grid, each tool's gate, the Device AI screen, onboarding — reads from its single
cached snapshot.

**It never infers support from `Build.MANUFACTURER` or `Build.MODEL`.** Every GenAI answer
comes from that feature's own runtime status call:

- **Prompt / Image questions** — `GenerativeModel.checkStatus()`
- **Summarize / Rewrite / Proofread / Image Description** — `checkFeatureStatus()` on the
  corresponding client
- **Transcription** — `SpeechRecognizer.checkStatus()` for `MODE_BASIC` and
  `MODE_ADVANCED` separately, with a narrow platform fallback (see above)
- **Text recognition** — available unconditionally; the model is bundled in the APK, so
  there is nothing to probe
- **Translation** — available unconditionally; individual language packs are reported
  separately by the Translate and Models screens

Those statuses map onto six app-level states — `AVAILABLE`, `DOWNLOADABLE`,
`DOWNLOADING`, `UNSUPPORTED`, `TEMPORARILY_UNAVAILABLE`, `ERROR` — plus `UNKNOWN` for
"not checked yet". `UNKNOWN` renders as *Checking*, never as *unsupported*, so the app
never tells you your hardware cannot do something before it has actually asked.

Complete snapshots are cached for 60 seconds using elapsed realtime, so opening Home,
then a tool, then the capability screen does not re-probe AICore three times. Checking one
tool does not renew other tools' results. Checks and publication are serialized so an
older response cannot overwrite a newer one. Refresh bypasses the cache; completed model
downloads request a fresh task check. Cancellation or failure does not renew full-snapshot
freshness, and wall-clock adjustments do not change its expiry.

Nothing is probed at process start: `LocalAiApplication` does no work, and the first
capability check happens when a screen needs one.

---

## Architecture

MVVM with a clean-ish separation, in a single Gradle module.

```
com.localai.toolkit
├── ai/
│   ├── engine/        AiEngine interface + request/response types  ← the seam
│   ├── capability/    DeviceAiCapabilityManager + default impl
│   ├── gemini/        ML Kit GenAI implementations, error mapping, client lifecycle
│   ├── mlkit/         Text recognition and translation
│   └── fake/          FakeAiEngine, FakeCapabilityManager
├── core/
│   ├── designsystem/  theme, spacing, motion, shared components
│   ├── navigation/    routes, tool catalog, cross-tool handoff
│   ├── ui/            app shell, failure → message mapping
│   └── util/          clipboard, share, image decoding, formatting
├── data/
│   ├── local/         Room database + DataStore
│   └── repository/    repository implementations
├── domain/
│   ├── model/         capability, failure, history and settings models
│   ├── repository/    repository interfaces
│   └── usecase/       deterministic developer tools (pure Kotlin)
├── di/                Hilt modules
└── feature/           one package per screen: Screen + ViewModel + UiState
```

Three decisions do most of the work:

**1. `AiEngine` is the only seam.** No screen, ViewModel or test imports an ML Kit or
AICore type. `di/AiModule.kt` is the single file that names `GeminiNanoAiEngine`. That is
what lets every ViewModel be unit tested, every Compose preview render real states, and
the whole app run on an emulator with no AICore.

**2. Failures are mapped at the boundary.** `GenAiFailureMapper` and `MlKitFailureMapper`
turn vendor error codes into a small `AiFailure` vocabulary — `Busy`, `QuotaExceeded`,
`BackgroundBlocked`, `ModelNotDownloaded`, `NotEnoughStorage`, `NeedsSystemUpdate`, and so
on. One mapping in `core/ui/AiFailureMessages.kt` turns those into sentences. A user never
sees a stack trace or an error code; a developer can switch on **Settings → Verbose error
details** to see the mapped technical line underneath.

**3. Tool-to-tool data travels in memory, not in routes.** Extracted OCR text can be very
long and image URIs must not be re-encoded into a navigation argument, so `ToolHandoff`
carries a one-shot payload that the destination consumes exactly once.

Incoming shares are separate: while a share is still waiting in the action router,
its normalized text or URI is included in Android activity saved state. This preserves
the latest share across OS process death even when Android supplies the original launch
intent. Consumed/dismissed shares are not restored. This is not a history entry, does not
save file bytes, and does not create or extend URI permissions. Unsaved tool drafts and
in-memory handoffs are not guaranteed to survive process death.

### Inference lifecycle

For text/image features, `GenAiClientProvider` caches clients keyed by their options.
Inference, downloads and status checks hold use-scoped leases. Changing options or
releasing a screen retires its cached client, but it is closed only after its last active
user finishes or cancels; a status check cannot close another operation's client.
Tool ViewModels cancel their own jobs and request release when they are cleared.
Idle clients remain reusable until replaced/released. Native SDK concurrency still
requires supported-device verification; leases govern app-side lifetime, not model capacity.

Text and image Prompt requests are built only when their response flow is collected,
on the IO dispatcher and inside the mapped error boundary. Setup failures therefore use
the same screen error handling as inference failures, without allocating a model client
for a rejected request. Cancellation and downstream collector errors remain distinct.
Automated tests inject request-building faults; native SDK image validation and model
input limits still require device testing.

Speech uses separate operation-scoped clients: status checks, model downloads and
recognition each close their own client when their operation ends. Stop/release target
only the active recognition session, so recording cleanup does not close a download's
client. Clear followed by Record waits for the previous collection's cleanup. The Android
on-device microphone fallback is Basic-only; Advanced never silently selects that
fallback. App-side lifecycle and concurrency regressions run on the JVM; native AICore
concurrency, microphone release and background behavior still require device testing.

Speech download Completed/Failed events end collection before terminal UI state is
published. An empty stream reports a retryable failure. Check status remains available
during progress and can reconcile a lost completion callback without downloading again.
A failed post-download capability refresh is a separate, mode-specific check error;
its retry does not repeat a completed download. Queued audio waits for the active
download to finish or be reconciled, even when an older check reported ready.

---

## Tech stack

Every version below was checked against Google Maven / Maven Central before being written
into `gradle/libs.versions.toml`.

| | |
| --- | --- |
| Android Gradle Plugin | 9.4.1 |
| Gradle | 9.6.0 |
| Kotlin | 2.4.20 (AGP 9 built-in Kotlin; no separate `kotlin-android` plugin) |
| KSP | 2.3.12 |
| Compose BOM | 2026.09.00 |
| Hilt | 2.60.1 |
| Room | 2.8.5 |
| DataStore | 1.2.1 |
| ML Kit Text Recognition | 16.0.1 (bundled) |
| ML Kit Translate | 17.0.3 |
| ML Kit Language ID | 17.0.6 |
| ML Kit GenAI Prompt | 1.0.0-beta4 |
| ML Kit GenAI Summarization / Rewriting / Proofreading / Image Description | 1.0.0-beta1 |
| ML Kit GenAI Speech Recognition | 1.0.0-alpha1 |

> **AGP 9 note.** AGP 9 provides Kotlin support itself. Applying
> `org.jetbrains.kotlin.android` alongside it is an error. Only the Compose compiler
> plugin and KSP are applied separately.

---

## Build instructions

Requirements:

- **JDK 17** to build, and **JDK 21** available for the unit tests (see Testing).
- Android SDK with **platform 37** and build-tools 36.
- No API keys or `google-services.json`. Debug builds need no signing setup.

```bash
./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.

```bash
./gradlew assembleRelease   # minified + resource-shrunk, unsigned
./gradlew bundleRelease     # Android App Bundle
```

### Release APK signing (Windows)

The v1.0.0 release uses a dedicated 4096-bit RSA key in `signing/mindkit-release.jks`.
The password is stored locally in `signing/release-password.dpapi`, protected by the
Windows account that created it. Both files and generated `dist/` APKs are Git-ignored.
They are not in this repository or in GitHub Releases. To build and sign a future
version after updating `versionCode` and `versionName`, run:

```powershell
.\scripts\sign-release.ps1 -Version 1.0.1 -Build
```

The script checks the package/version and ZIP alignment, signs with the same key,
and verifies the resulting APK. Back up the keystore **and** its password before
moving computers or resetting Windows. Run `scripts/copy-release-password.ps1` to
copy the password for storage in a password manager; the DPAPI file alone cannot be
decrypted by a different Windows account.

### A note on size

The debug APK is large (~135 MB) and the release APK is ~113 MB. Almost all of that is
**bundled ML Kit native libraries for four ABIs** — `libtranslate_jni.so` and
`libmlkit_google_ocr_pipeline.so` are together ~106 MB across `arm64-v8a`, `armeabi-v7a`,
`x86` and `x86_64`. R8 shrinks the app's own code from ~76 MB of dex to ~7 MB.

Shipping as an App Bundle is the answer: the `.aab` is ~56 MB and a device downloads only
its own ABI. That is a deliberate trade — the bundled text recognition model is what lets
the OCR tool work on first launch with no download and no network.

---

## Testing

```bash
./gradlew testDebugUnitTest          # JVM unit tests
./gradlew connectedDebugAndroidTest  # Compose UI tests (needs a device or emulator)
```

**Unit tests run on JDK 21.** Robolectric refuses to build a sandbox for Android SDK 37 on
anything older, so `app/build.gradle.kts` points the `Test` tasks at a Java 21 launcher
while the app itself still compiles to Java 17 bytecode. The tests also need
`--add-opens` into `java.base`, which is configured in the same block. Robolectric itself
is pinned to SDK 34 in `app/src/test/resources/robolectric.properties`, because 4.17
cannot emulate SDK 37's internals yet.

What is covered:

- **Capability mapping** — every device profile (supported / unsupported / download
  required / unresolved) against what Home renders.
- **Error mapping** — every ML Kit GenAI error code onto its `AiFailure`, including that
  an *unrecognised* code degrades to `Unknown` rather than being guessed.
- **The gate** — that an unsupported device never renders a tool's UI.
- **Developer utilities** — JSON against published edge cases (trailing commas, unquoted
  keys, leading zeroes, precision), hashes against published digests, JWT decoding,
  Base64, URL codecs and timestamps.
- **History persistence** — against a real in-memory Room database, including that
  disabling history means nothing is written at all. Saved result controls observe
  their stored row: deleting it or clearing history makes the retained result
  saveable again without rerunning inference. Ask tracks each answer independently.
- **Share intent parsing** — including every case that must *not* open an action screen.
- **`FakeAiEngine`'s own contract**, since every ViewModel test depends on it.

`FakeAiEngine` and `FakeCapabilityManager` are production source, not test fixtures: they
back the Compose previews too, so a preview state cannot drift from what the real mapping
produces.

---

## Screenshots

> _To be added._ Suggested set: Home on a supported device, Home on a device without
> Gemini Nano, Extract Text with a result, Translate with a language pack download,
> Ask AI mid-stream, the Device AI capability screen, and Settings → Privacy.

---

## Known limitations

1. **Automated tests are not a complete physical-device check.** The JVM suite has
   one explicitly skipped language-dialog case because of a Robolectric runtime
   issue; its Android equivalent passed on an API 36 emulator. All 46 current
   instrumentation tests passed there, including Home/History/gate controls,
   language search/select/close/detect/swap, native WAV/AAC decoding/pipe cleanup,
   real bundled OCR/language identification, system-picker open/Back cancellation,
   and real MainActivity share/recreation, draft, result and tab-state regressions.
   A separate host test verifies latest pending-share recovery and no dismissed-share
   replay across genuine OS process kills (not just activity recreation).
   These tests do not prove real AICore inference, system picker grants, microphone
   behavior, successful provider URI grants or universal activity/process restoration. See `functional-audit.md`
   for current counts, coverage, and remaining checks.
2. **Gemini Nano paths have not been exercised on hardware.** The ML Kit GenAI APIs were
   integrated against the shipped artifacts — every class, method, constant and error code
   used was read from the decompiled AARs rather than assumed — but no device with AICore
   was available, so the success paths are unverified in practice. The unsupported and
   error paths are covered by unit tests.
3. **GenAI speech recognition is an alpha API** (`1.0.0-alpha1`). Availability depends
   on the device and its installed services; the app falls back to the platform on-device
   recognizer, which cannot transcribe files — only live microphone input. The Transcribe
   screen says so rather than hiding the option.
4. **Audio file transcription needs the GenAI recognizer.** Imported container/encoded
   audio is decoded locally using Android's available media codecs, downmixed and
   resampled to raw PCM-16/16 kHz/mono as required by the speech API, then streamed
   at playback speed through a cancellable in-memory pipe. No input or decoded
   audio cache file is written. Supported containers/codecs depend on Android;
   unreadable, empty or unsupported files show an audio-specific error. Converter
   and lifecycle tests run locally; native WAV/AAC/pipe instrumentation passed on
   API 36. End-to-end AICore transcription still requires supported-device verification.
5. **Summarization is bullet points only**, in three languages. See above.
6. **The Prompt API here has no reliable structured-output mode**, so Explain Error and
   Explain Code request their four sections in the prompt and render whatever the model
   returns. They do not parse the response into fields the model never promised.
7. **Conversation memory in Ask AI is prompt-concatenation**, not a managed chat session,
   because the Prompt API takes content rather than a session. Long conversations will hit
   the model's token limit.
8. **JWT signatures are not verified** — only decoded. Verifying would need the issuer's
   key, and showing "valid" without one would be actively misleading.
9. **`ui-test-junit4`'s `createComposeRule` is deprecated** in the version the Compose BOM
   pins, but the suggested `v2` replacement is not present in that artifact. Three
   deprecation warnings in instrumentation test sources are left visible rather than
   suppressed.
10. **No translations yet.** All user-facing text is in `strings.xml` and
    `generateLocaleConfig` is on, so adding a locale is a drop-in.

---

## Roadmap

- Camera capture for OCR (currently photo picker only)
- Text block/region selection in OCR results
- Batch OCR across multiple images
- Export history to a file
- Per-conversation persistence for Ask AI
- Structured output for the developer explain tools, once the Prompt API exposes a schema
  mode the app can depend on
- Wider language coverage as the GenAI features add languages
- Home screen widget for a quick Ask or Translate

---

## License

Third-party notices are available from **Settings → Open source licenses** and
**About MindKit → Open source licenses**. Google's
[OSS licenses integration](https://developers.google.com/android/guides/opensource)
generates the bundled notice resources from resolved dependencies during builds.
The debug build supplies its runtime dependency coordinates to the generator because
AGP does not generate the dependency report used by that plugin for debug variants.
Release builds use AGP's report. `LicenseViewerTest` checks notice byte ranges and
the real viewer's list/detail/Back interaction; these tests do not depend on ML Kit.

This does not choose a license for MindKit's own source code:

> _To be chosen._ No license has been applied yet. Note that the ML Kit and Google Play
> services dependencies carry their own terms — see
> [ML Kit terms](https://developers.google.com/ml-kit/terms).
