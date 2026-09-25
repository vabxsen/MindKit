# MindKit functional audit

Status: **latest automated verification passed (2026-09-25); physical-device checks remain outstanding**.

Latest result: **487 JVM tests passed, 1 existing explicit skip; 46 Android emulator
tests passed; lint 0 errors/4 dependency-update advisories; debug, instrumentation and
unsigned minified release builds passed**. Both the pending-share recreation crash
and stale-share recovery after actual OS process death are fixed and verified.
This completes the current automated regression pass, not every hardware/inference check.

Scope: every screen, button, input, navigation/handoff, failure recovery, persistence,
and supported-device behavior. Source review and fake-engine tests do not prove real
ML Kit/AICore inference or Android system picker/permission behavior.

## Verification environment

- Windows; project `D:\My Apps\ai app`; Android SDK `C:\Android\Sdk`.
- No attached Android device and no installed emulator at the initial check.
- Recheck on 2026-09-24 found runnable AVDs and API 35/36 images installed.
  The existing 37 Android tests subsequently passed on a headless, read-only
  API 36 `medium_phone` session. No physical phone or Gemini Nano test ran.
- User requested continuing with automated tests for now.
- JVM Android tests use Robolectric SDK 34; app compiles/targets SDK 37, min SDK 26.
- Baseline unit tests and APK builds passed. Baseline lint found two unguarded
  API-31 speech calls. Guards fixed both errors. Full build/lint verification after
  the first fixes passed (0 lint errors, 50 warnings).
- Compose semantics interaction tests now run locally through Robolectric;
  `FeatureControlsTest` now covers 13 interactions. This does not emulate real
  AICore inference, external providers, or OEM permission/picker behavior.

## Fixed and regression-tested in this audit

- Share recreation: the real MainActivity test reproduced navigation to the share
  router before Scaffold had installed the restored navigation graph. The handler
  now waits for the first back-stack entry; clearing the share cancels that wait.
  Two deterministic JVM cases failed before the fix and pass afterward, covering
  delayed graph installation and cancellation before readiness. The existing warm
  and cold share navigation cases also pass. Full post-fix gates are recorded below.
- Translation: Clear/input/language changes cancel stale translation; Clear stops
  language detection; swapping uses the result and resets Save state.
  Evidence: `TranslateViewModelTest` (4 tests passed).
- Speech: shared audio waits for availability; failed files retry; file work can
  be canceled; Stop is bounded and duplicate taps coalesce; leaving recording UI
  releases mic ownership; an explicit mode choice survives the first check;
  duplicate downloads coalesce and queued audio resumes after model installation.
  Evidence: `TranscriptionViewModelTest` (7 tests passed). Real mic/picker behavior
  remains unverified.
- Image AI: Clear/replacement cancels image loading; late decodes cannot replace
  new state; changing question/mode cancels obsolete inference; Stop is wired to
  the UI; loading has a Cancel action; displayed bitmaps are no longer manually
  recycled while Compose/native consumers may still reference them.
  Evidence: `ImageViewModelTest` (6 tests passed).
- Translation/OCR/GenAI exception mapping preserves coroutine cancellation instead
  of presenting cancellation as an inference failure. Engine boundary changed;
  real vendor calls still require device verification.
- Capability reporting: basic speech uses the actual provider from the engine;
  pre-Android-12 devices are not advertised as supporting on-device speech.
- Shared model gate: rapid download taps coalesce; failed downloads are not hidden
  by a stale DOWNLOADING capability; a status-check action is available while
  downloading. `GenAiFeatureGateTest` (2 tests) and expanded `GateStateTest`
  (13 tests) passed in the full run.
- The Compose tests reproduced an empty-state layout consuming the download
  panel's available height and hiding its error/button. The panel now uses a
  compact empty state; the formerly failing visibility/click test passes.
- Developer tools: input/language changes cancel stale explanation results;
  successive UUID/hash runs reset Save state; run is disabled during generation;
  timestamp input no longer forces a numeric-only keyboard when ISO input is
  supported. `DeveloperViewModelTest` adds 5 tests covering these action flows.
- Base64 rejects punctuation-only/corrupt input and malformed UTF-8 instead of
  silently discarding bytes; whitespace and unpadded valid data still decode.
  Extreme ISO/JWT expiry values no longer crash; minimum-long timestamps no
  longer overflow to a false result; extra JWT segments are rejected. Four
  additional utility regression tests cover these cases.
- Chat: regression tests reproduced the Stop/new-conversation generation race;
  generation ownership now prevents old finalizers from changing new turns.
  Regenerate targets the selected answer and its original context, preserves
  later turns and the draft, and cannot run during another generation.
  Evidence: 4 `AskViewModelTest` cases + per-answer button semantics test.
- Save across all 9 tools now uses `HistorySaveController`: in-flight duplicate
  taps coalesce, disabled history/storage failures are explained by a snackbar,
  completed results cannot be repeatedly saved, incomplete results are disabled,
  and late completions cannot mark a different displayed result saved.
  Evidence: 4 controller tests, text-tool save/clear tests, disabled-history
  snackbar and streaming-result Save semantics tests. Full per-tool persistence
  integration coverage is still being expanded.
- Summarize retains the original summarized input for history; Rewrite retains
  the original style; Transcribe retains the recognition mode that produced the
  result. Input/option edits cancel obsolete text-tool work. Proofreading an
  unchanged input can no longer reappear as a correction after an edit.
  Evidence: 7 `TextToolActionsTest` cases for source identity, options, clear,
  comparison/apply, persistence, and text handoffs.
- Incoming shares now navigate the current graph instead of recreating the
  activity. The router observes new content, validates the selected tool against
  the current share, consumes once, and handles system Back like Cancel.
  Cancel returns to the prior destination. Text CharSequences, subject fallback,
  and ClipData URIs are supported. Evidence: 4 router tests, 3 additional parser
  tests, 2 Compose cold/warm navigation tests. Real activity process-death/URI
  grant behavior still needs Android verification.
- Models now exposes Retry for list errors, clears stale errors after successful
  refresh, and disables mutations while checking installed languages.
  Evidence: 3 ViewModel tests + a Retry button semantics test.
- OCR now uses the injectable image loader, cancels obsolete decode/recognition,
  ignores late native callbacks, and leaves bitmap lifetime to its consumers/GC.
  Clear no longer recycles a bitmap still referenced by Compose or ML Kit.
  Evidence: 5 OCR ViewModel tests cover cancellation, unreadable/no-text images,
  Retry, Save, shared input and text handoff.
- Onboarding coalesces Finish and device checks, exposes Skip during a pending
  check, and supports retrying failed checks or preference writes. Four ViewModel
  tests pass; Compose tests cover the three-page flow, Check, Skip and Retry.
- Settings now confirms Clear history as well as Clear all local data; coalesces
  clearing, serializes preference changes, reports storage failures, and exposes
  switches as one accessible toggle each. Five ViewModel tests cover persistence,
  reset versus history-only deletion, failure/retry and duplicate actions.
- History has loading/error/retry states for list and detail, preserves failed
  deletion requests for Retry, and disables duplicate deletion while running.
  Detail no longer flashes “no longer available” during its initial load.
  Four ViewModel tests cover query/filter/reset, deletion and load recovery.
- `AppControlsTest` adds 12 real Compose interactions: settings confirmations,
  toggles, disabled controls and navigation; onboarding progression/escape;
  history row open versus nested Delete, delete-all confirmation, search/Clear,
  loading/Retry, and actual clipboard plus ACTION_SEND chooser payload.
- Device AI no longer calls a downloadable/downloading/error status “Available”.
  Refresh coalesces, fails visibly and retries; cancellation clears the manager's
  refreshing marker. Two unit tests cover status mapping and refresh recovery.
- A launcher intent arriving while a share is pending no longer replaces its
  recovery intent. This is source-reviewed; activity process-death/grant behavior
  is still unverified on Android.
- Home no longer depends on a successful history query to show its tools. Recent
  history has independent loading/failure/Retry states; capability failures are
  retryable and duplicate refresh taps coalesce. Model-required and limited
  devices no longer show “Checking local models” after their check finishes.
  Two ViewModel tests and five Home Compose tests pass, including all nine tool
  routes, quick-action routes, disabled unsupported tools and exact recent IDs.
- Bottom tabs now anchor to Home after onboarding is removed, rather than the
  removed graph start. Three real-shell Compose navigation tests pass for repeated
  switching, restored History input, onboarding-to-Home, and tool detail/Back.
- Transcription download state/jobs are now tracked per mode: switching modes
  cannot show the other mode's progress/error. Download Retry retries the download;
  a failed local download is not hidden by a stale DOWNLOADING status. External
  downloads have Check status. Availability exceptions recover through Retry.
  Three new ViewModel and two Compose cases are included in the full gate.
  The first run passed 230/231; the failed test looked for “Retry” rather than
  the actual “Download model” recovery button. Its selector/callback assertion
  was corrected and the full gate then passed all 231 tests; no product assertion
  was removed.
- Preferences reads no longer stop observing after an IOException. They emit a
  transient read-health flag with history saving disabled, preserve known values,
  and recheck after five seconds. Root startup has an explicit loading/error/Retry
  UI; a later failure retains the existing graph and draft under its error dialog.
  Six targeted tests pass (flow recovery/cancellation, root model and actual UI).
- JSON parsing is bounded at 100,000 input characters and 128 container levels;
  formatting rejects expansion beyond 2,000,000 characters. Developer Run explains
  oversized input without truncating it, and a shorter input can run afterward.
  Strict validation now rejects non-ASCII numeric digits and signed unicode
  escapes. Four new JSON/developer regressions pass in the targeted run.
- `ResultActionsTest` exercises Copy, system Share payload and Save callback on
  all nine tools, including corrected versus original proofreading text, plus
  Summary's Translate/Rewrite handoffs. It reproduced Developer's internal
  `VALID_JSON` token escaping into clipboard output. Success output is now
  human-readable when copied/shared/stored; a history regression covers storage.
  Initial Ask/OCR failures were invalid test selectors/fixture data; both were
  corrected. The full corrected suite passed all 252 tests, including these actions.

## Complete coverage ledger

### About / management controls pass (2026-09-23)

- About's “Open source licenses” no longer opens only ML Kit terms or silently
  swallows launch failures. Both Settings and About open Google's actual bundled
  notice viewer; a missing/blocked activity shows a snackbar. Settings now has a
  distinct “About MindKit” destination instead of mislabelling About as licenses.
- Added `oss-licenses-plugin` 0.13.0 and `play-services-oss-licenses` 17.5.2 using
  the [official integration guide](https://developers.google.com/android/guides/opensource).
  The skill-assisted documentation/source check guided that integration rather
  than maintaining a handwritten dependency notice list. Inspection caught the
  upstream plugin's debug-only placeholder: a cacheable debug dependency-report
  task now supplies actual resolved runtime coordinates to the same extractor.
  See [upstream task behavior](https://github.com/google/play-services-plugins/blob/main/oss-licenses-plugin/src/main/groovy/com/google/android/gms/oss/licenses/plugin/OssLicensesPlugin.groovy).
- Generated resources contain 383 debug entries and 380 release entries. Debug
  resource tests validate every entry's byte range/content and reject the placeholder;
  the release resource range check found 0 invalid entries (1,703,781 bytes).
  A real SDK activity test opens the list, selects a dependency, verifies its license
  reference, and returns via Back. The first selector missed the SDK's trailing
  line ending; it now matches the observed accessible label.
- Six management UI tests cover About/Back/device-information navigation and
  license launch failure feedback; Privacy scrolling/Back; Device AI Refresh/Retry/
  busy guards/Back; Models search, correct-language Download/Delete and busy guards;
  and every history filter chip's selected/deselected semantics. Settings has a
  separate regression for launching licenses directly. The Models search test's
  initial ambiguous selector matched both the query and result; it now targets
  the result label rather than the editable field.
- Latest full gate passed in 3m 59s: 281 declared JVM tests, **280 passed,
  1 pre-existing explicitly skipped language-dialog case, 0 failures/errors**;
  lint **0 errors, 49 warnings**; debug, instrumentation and minified unsigned
  release APKs built. `aapt2` confirms both license resources survive release
  shrinking; both packaged resources' SHA-256 hashes match their generated inputs.
  This is build evidence, not execution of the release APK on a device.
- The release gate emitted vendor protobuf/Tink generated-code warnings and
  Compose stack-trace mapping warnings for the pre-obfuscated license SDK. These
  were not suppressed; the build completed. Dependency-warning review remains open.

### Latest option / handoff and translation pass (2026-09-23)

- Added real Compose callback tests for Summary lengths/types/Run/Clear; Rewrite
  styles/compare/accept/regenerate/Clear; Proofread types/Run/Apply/Clear; all ten
  developer-tool destinations and codec directions/UUID counts/hash algorithms/
  explanation languages/Now; Ask input/Send/Stop/New conversation/Info/Close;
  and Image modes/suggested questions/Run/OCR handoff/Clear.
- Added output handoff UI tests for Rewrite, Proofread, OCR, Image and Transcribe.
  These assert the selected destination callback, not real vendor inference.
- Translation now reads current state after the suspended installed-model check,
  preserving a draft/language selection edited while the check was running. Model
  checks coalesce, refresh on screen resume and after a missing-model inference
  failure, and expose their own Retry instead of retrying inference. Run is disabled
  while the model list is unknown/failed. Download Retry retries the failed pack.
- Translation and language detection reject late non-cancellable completions after
  Clear. Unexpected inference/download exceptions leave an actionable error rather
  than a stuck progress flag. Five new ViewModel regressions (nine total) passed
  in the focused run, alongside the model-check/download Retry UI tests.
- Rewrite's initial Clear test failure was its transient acceptance snackbar
  covering the button; the test now advances past the snackbar before tapping.
- **One UI workflow remains unverified:** language-dialog search/select/Close.
  Robolectric 4.17 + Compose 1.12.1 on SDK 34 never settles dialog measure/layout.
  An independent Material AlertDialog containing only an OutlinedTextField also
  reproduces this, with both Compose test dispatchers. Native graphics, fixed
  list height, a non-lazy list and a basic dialog did not resolve it. All production
  layout experiments and temporary diagnostic reflection were removed. The JVM
  case is explicitly `@Ignore` (not counted as passed); equivalent
  `LanguagePickerInstrumentedTest` is added for Android execution, still pending
  a device/emulator. This does not establish that the picker works on-device.

The full verification gate passed after this pass: 272 declared JVM tests,
**271 passed, 1 explicitly skipped, 0 failures/errors**. Lint: **0 errors,
50 warnings**. App and instrumentation APKs built; instrumentation did not run.

“Pending” includes exercising the actual UI, not just locating a callback.

| Surface | Actions / behavior to verify | Current evidence / remaining work |
| --- | --- | --- |
| Onboarding | advance, skip/finish, persistence, Back | 5 VM tests + progression/Check/Skip/Retry UI tests; accepted completion persists after screen disposal without late navigation. Back/restoration/device checks remain |
| App shell / Home | tabs, all tool cards, recent-history cards, restored state | 3 VM (including real cache integration) + 5 Home UI + 3 real-shell navigation tests pass; Home has no category/search controls. Partial-cache completion and forced Refresh verified. Physical Back/process restoration remain device checks |
| Ask | send, stop, retry correct turn, new conversation, copy/share/save, input limit, streaming/error | Race/selected-answer Retry fixed; 7 VM tests plus Regenerate, input/Send/Stop/New/Info/Close, clipboard/Share/Save callback UI tests pass. Rejected setup retains the original question; retry preserves the next draft. Each answer saves independently even with identical text, and its saved flag follows its history row. Native input-limit/device checks remain |
| Summarize | input/options, summarize, cancel/clear, retry, copy/share/save, handoffs | Provenance/cancellation fixed; lengths/types/Run/Clear and clipboard/Share/Save/handoff UI pass. Real-engine/input-limit/error integration remains |
| Rewrite | input/style, rewrite, compare, accept, clear, retry, copy/share/save, handoffs | Original metadata preserved; styles/compare/accept/regenerate/Clear and clipboard/Share/Save/handoff UI pass. Real-engine/error integration remains |
| Proofread | input/type, run, compare/accept, clear, retry, copy/share/save, handoffs | Unchanged-result editing fixed; types/Run/Apply/Clear and clipboard/Share/Save/handoff UI pass. Real-engine/error integration remains |
| Image AI | photo picker, mode/prompt chips, run/stop, clear/change image, retry, copy/share/save, OCR/text handoffs | 8 VM tests; modes/prompts/Run/Stop/Clear/OCR/text handoff and result-action UI tests pass. Setup rejection retains the image/question, exits loading and allows another run; visible Retry and private technical details are tested. History deletion re-enables Save without inference. System picker/native inference integration remains |
| OCR | photo selection, decode, recognize, no-text state, clear/change/retry, copy/share/save, handoffs | 6 VM tests + downsample tests; bitmap lifetime/cancellation fixed. Deleted results can be saved again without recognition. Uses photo picker, no camera capture button; actual provider/native inference remains unverified |
| Translate | source/target/search, detect/accept, swap, run/clear/retry, model download, copy/share/save | 17 VM tests pass; refresh/draft preservation/retry/cancellation and preference read/write ordering/recovery fixed. Result actions, Save after history deletion and model/download/preference Retry UI pass. Language-picker JVM test explicitly skipped, but equivalent search/select/close/detect/swap/same-language Android test passes on API 36. Five cross-screen lifecycle/reset and six queue tests verify accepted writes after navigation and reset ordering; native translation/model operations/process death remain |
| Transcribe | mode, mic permission, record/stop, file picker/cancel, retry, download, clear, copy/share/save, handoffs | 24 VM tests, 20 engine ownership/fallback tests, 11 platform-session tests and download UI cases; 22 converter/decoder/pipe/error-picker regressions plus 2 active Stop/Cancel semantics tests pass. Queued input precedence, availability Retry and Save after history deletion fixed. 9 download/check recovery tests and 4 additional Check status UI cases pass; stale-ready audio launch is blocked. Native WAV downmix, AAC decode and unread-pipe cancellation (3 Android tests) pass on API 36. Native recognition, microphone, system picker, concurrency and device lifecycle remain |
| Developer tools | JSON format/validate, Base64 directions, URL directions, JWT, UUID count/generate, hash selection, timestamp/Now, Explain Error/Code/language, clear/retry/copy/share/save | Utility/VM tests cover normal and large/deep/malformed inputs; readable validator exports fixed; all ten destinations, directions/counts/algorithms/languages/Now/Run/Clear and result-action UI pass. Actual-device responsiveness remains |
| History | search, type filters, open, copy/share, delete one/all, confirmations, empty states | 4 VM tests + UI row/delete/confirm/search/Clear/load/Retry, every filter chip selected/deselected, and real clipboard/Share payload tests; 3 mutation-ordering tests and 9 real Room tests pass. Pending saves no longer undo later Clear; restoration/cross-store reset remains |
| Settings | theme, dynamic color, history toggle, verbose errors, models/privacy/about navigation, clear history/all data confirmations | 5 VM tests + UI confirmation/toggle/disabled/navigation tests; shared app-owned queue fixes navigation cancellation and cross-screen reset ordering; five lifecycle and six queue tests pass. Physical-device/process-death behavior remains |
| Models | language search, installed indicators, download/delete, busy state, retry, refresh | Retry added and stale error fixed; VM list/retry/download/delete/busy/search tests plus actual UI Retry/search/correct-language Download/Delete/busy guards pass. Actual vendor model operations unverified |
| Device AI | refresh, accurate task/provider/model/status details, error recovery | Speech and Nano readiness reporting corrected; refresh coalescing/failure/retry + status tests; UI Refresh/Retry/busy guards/Back pass. 11 shared-cache timing/order/cancellation tests verify full versus task freshness. Real-device capability checks remain |
| About / Privacy | navigation and every link/action | About license launcher/navigation/Back and failure feedback pass; real SDK license list/detail/Back plus notice-resource tests pass. Privacy has no links/actions except Back: scrolling/readability/Back test passes. Device font scaling/rotation remains |
| Incoming shares | cold/warm launch, text/image/audio routing, cancel, repeated share, rotation/process state, permissions | Parsing, router repeat/cancel/replacement tests and cold/warm graph tests pass; real activity recreation/process death and URI grants pending |
| Shared result actions | Copy confirmation, system Share, Save once, disabled-history feedback, no stale Save completion, Save after history deletion | 11 shared Save controller tests; deletion/re-save integration covers all nine tools, independent Ask answers (including identical content), real Room and the visible Saved-to-Save button transition. Existing clipboard/Share/Save callbacks and failure feedback cover all nine tools and History. Android 12 original-text copy confirmations and modern-Android no-duplicate confirmation tested. Remaining device integration checks pending |
| Shared AI gate | unknown/available/unsupported/downloading/error, download/retry/status refresh | 18 controller, 15 state tests and button semantics pass; thrown setup/stream/check failures, terminal cleanup, stale-check ordering and stuck-download recovery covered. Progress plus failed-check feedback tested in Compose; device installation remains unverified |
| Accessibility / lifecycle | semantics, keyboard/touch focus, disabled controls, scrolling, back/rotation/background | UI and actual-device checks remain |
| Text/image client lifetime | overlapping inference/download/check, option replacement, screen release, cancellation | 9 lease-cache tests and 9 real provider/engine/capability tests with controlled Prompt SDK and request-history inputs pass. App-side retirement waits for all users; cold request setup, mapped rejection before client allocation, cancellation and downstream error transparency are covered. Native feature SDK concurrency and inference remain unverified |

## Next verification steps

1. Latest full verification: `gradlew.bat testDebugUnitTest lintDebug connectedDebugAndroidTest assembleDebug
   assembleDebugAndroidTest assembleRelease --no-parallel --max-workers=1 --console=plain`
   **passed**. JUnit XML reports:
   **488 tests: 487 passed, 1 explicitly skipped, 0 failures, 0 errors**
   (2026-09-25); lint completed
   successfully with **0 errors, 4 dependency-update advisories**.
   Debug, instrumentation and unsigned minified release APKs built, but building
   the instrumentation APK is not executing it.
   Repeat this gate after subsequent fixes.
   The full gate includes startup preferences recovery/draft retention, JSON
   input/depth/expansion limits and strict validation, all nine tools' Copy/Share/
   Save callbacks, readable validator exports/history, option controls/handoffs,
   translation model refresh/retry/draft preservation, About/Privacy/management
   controls, actual license list/detail/Back and notice integrity, translation
   preference recovery/ordering, history mutation ordering, all tool/History Copy/
   Share failure feedback, original-text exports, Android 12 copy confirmation,
   audio conversion/stream cleanup, invalid-audio picker recovery, speech setup/
   Stop failures, terminal stream handling, Android recognizer ownership, isolated
   speech operations, Basic-only fallback, rapid Clear/Record sequencing, shared
   download/check/retry recovery, stale-check isolation, cold SDK setup failures,
   queued-audio precedence, active Stop/Cancel visibility despite status errors,
   text/image client lease retirement, SDK cancellation propagation, complete-cache
   freshness, monotonic expiry, serialized task/full Refresh ordering and saved-result
   invalidation after history deletion (all nine tools, Compose and real Room),
   plus independent identical-answer Save, same-answer double-tap protection and
   application-owned preference/reset ordering across screen disposal, speech download
   terminal/check recovery, queued-audio gating against stale readiness, and cold
   text/image request setup with mapped failure/input retention/retry regressions.
   The latest combined gate completed successfully in 4m 40s. The language-dialog JVM test
   remains skipped, but its Android equivalent passed in the 46-test
   emulator run. Compilation and emulator control tests do not establish physical-
   device inference, system picker, microphone or complete lifecycle behavior.
2. Expand Compose interaction tests under Robolectric to the remaining screens.
3. Expand per-tool integration coverage for Save/result provenance and cancellation.
4. Exercise warm/cold incoming shares and all developer/settings/history actions.
5. Keep device-only limits explicit. Do not mark the original goal complete on
   fake-engine/unit coverage alone.

## Priority follow-ups

- See the latest completion pass at the end of this ledger before interpreting older
  pending items: real pending-share process death, bundled OCR/language identification,
  and system picker cancellation now have new executed checks. Full hardware inference,
  successful provider URI grants and broad accessibility are still separate checks.
- Finish the complete screen/action ledger above. A passing subset is not a
  substitute for every button and supported workflow.
- OCR lifecycle/native inference remains device-only; automated cancellation and
  bitmap lifetime ownership regressions now pass.
- Developer input/nesting/output expansion and option controls now have automated
  coverage; actual-device responsiveness checks remain.
- The explicitly skipped JVM language-dialog test remains a test-runtime limitation.
  Its Android equivalent now passes on API 36, verifying in-app language selection
  rather than an Android system file/photo picker. Keep the skip visible; it is not
  an additional prerequisite to that now-verified emulator control flow.
- Translation model refresh/Retry, preference read/write failure recovery and
  initialization/selection/write ordering have regression coverage. App-owned
  SettingsActions now preserves accepted writes after navigation and orders them
  against other screens' reset actions (see persistence pass). OS process death,
  actual disk behavior and partial cross-store reset recovery still need device
  verification; the in-memory queue is not a durable transaction.
- Transcription now decodes picked audio locally into PCM-16/16 kHz/mono and uses
  a paced, cancellable pipe instead of forwarding encoded/container bytes. Its
  converter, adapter and stream lifetime have automated coverage (see audio pass
  below). Native WAV/AAC/pipe instrumentation now passes on an API 36 emulator.
  End-to-end recognition and physical-device codec support still require a supported
  phone; the emulator does not establish those capabilities.
  Platform setup/cancellation/final-response cleanup now has regression coverage.
  Speech clients are now operation-scoped; Stop/release target only recognition,
  status/download cancellation closes only its owner, and Advanced no longer
  selects the basic Android fallback. Direct engine overlap/error tests and rapid
  Clear/Record sequencing pass (see ownership pass). Verify native SDK concurrent
  clients, creation cost and actual microphone release on a supported device.
  Queued shared audio no longer overrides a newer accepted recording/file, and
  availability Retry no longer requeues an old file forever. Active Stop/Cancel
  survives status/download errors. Speech terminal collection, post-download check
  Retry, lost-callback recovery and stale-ready queued-audio gating now have focused
  regression coverage (see speech download pass). Restored navigation, native
  installation callbacks and device lifecycle behavior remain unverified.
- Text/image GenAI clients now use option-keyed leases across inference, download
  and status calls. App-side retirement/cancellation/cleanup tests pass; verify
  actual SDK resource release, overlapping native feature clients, backgrounding,
  and model capacity on supported hardware. Lease tests do not prove native inference.
- Text/image streaming request construction now occurs inside the mapped cold flow,
  before allocating a client. Controlled request-builder faults and error recovery
  in Ask, Image AI and both developer explainers are tested. Native ImagePart/request
  validation and real model input limits still need supported-device verification;
  injected faults are not evidence of a native constructor defect.
- Shared GenAiFeatureGate now handles thrown setup/stream/status failures, ends
  terminal streams and offers Retry without re-downloading after a failed status
  check. Check status can recover a lost completion callback, retains active
  progress on check errors and cannot cancel a newer request. Prompt download
  client creation is cold and inside its error boundary. Native download callback/
  future ordering and native shared-client behavior still need verification.
- Expand shell navigation, per-tool copy/share intents, cross-screen settings
  persistence and actual-device accessibility beyond the new Compose tests.
- Capability-cache freshness now belongs only to a completed full snapshot and
  uses elapsed realtime. Task/full resolution and publication are serialized;
  partial-to-full refresh, stale-result ordering and Home integration regressions
  pass. Native provider latency, process restoration and backgrounding remain.
- The new share navigation tests should continue to match LocalAiApp's Scaffold
  layout (now verified in the targeted rerun); real restored pending-share behavior
  remains unverified. Launcher-intent replacement is fixed at the activity source
  boundary; no device/process-death claim is made.
- Root settings read recovery is now tested, including retained drafts and
  history-disabled fallback. Verify actual DataStore corruption/storage failure
  behavior on-device; the automated tests inject equivalent flow failures.
- About's misleading license link and silent failure are fixed and tested. Review
  release-build vendor protobuf/Tink and Compose mapping warnings; do not suppress
  them or equate a successful APK build with full runtime verification.

## Persistence ordering pass (2026-09-23)

- Reproduced two failures before fixing translation: a slow preference read replaced
  an explicit language choice, and an older slow write overwrote a newer pair.
  Writes now serialize; initialization stops when the user chooses a pair or starts
  translating. Model checks and incoming text no longer wait on settings storage.
- Added seven translation VM regressions (16 total): slow initialization, ordered
  writes, starting translation before restoration, failed write/Retry coalescing,
  stale write failure, read-failure recovery, and recovery fallback/handoff edits.
  A separate Compose test verifies preference Retry does not translate/download.
- Reproduced Clear completing while an earlier Save was still reading preferences.
  The singleton history repository now serializes Save/Delete/Clear before dispatch
  or preference-read suspension. Three controlled tests cover ordering, cancellation
  and failed-clear recovery; eight existing real Room persistence tests also pass.
- Targeted regression gate and full serial gate passed (292 declared tests, 291
  passed, 1 explicitly skipped; lint 0 errors/49 warnings; debug, instrumentation
  and minified unsigned release APKs built). No device execution is claimed.
- The initial parallel gate completed tests/debug builds but lint crashed while
  reading a missing generated release Hilt source. Repeating the full gate with
  `--no-parallel --max-workers=1` passed, without disabling checks or suppressing
  diagnostics. Use the serial gate for subsequent combined verification; investigate
  the lint/generated-source overlap separately if parallel builds remain unreliable.

## Copy/Share failure handling pass (2026-09-23)

- Reproduced a missing clipboard service producing no visible Copy feedback, and
  an unavailable share-sheet activity throwing through the Summary button.
- Shared `TextActionHandler` now checks platform results, reports Copy/Share
  failures without claiming success, and confirms successful copies only below
  Android 13. All nine tools, History, and Rewrite/Proofread original-text panels
  use it; repeated feedback replaces this handler's prior message rather than
  queueing outdated confirmations. Copy/Share do not alter results or Save state.
- The platform utilities catch runtime service/policy/launch failures. Sharing
  remains a plain-text system chooser with no preselected recipient; application
  contexts get NEW_TASK, while wrapped Activity contexts retain their existing task.
- Targeted tests passed for all tool/History failure messages, successful clipboard
  and share payloads, missing service/activity, policy errors, both launch contexts,
  Android 12 copy confirmations and modern Android's no-duplicate confirmation.
  Original-text Share payload regressions also passed in the final full gate.
- Android 12.1 (SDK 32) Robolectric runtime was added to the local test cache for
  three legacy-copy cases; this is still a JVM test, not a physical-device run.
- Full serial verification passed: **315 declared tests, 314 passed, 1 explicitly
  skipped, no failures/errors**; lint **0 errors, 49 warnings**; debug,
  instrumentation and unsigned minified release APKs built. This pass added 23
  regressions. Compose lint caught a configuration-unaware resource lookup in the
  new helper; it was corrected with `LocalResources` before the successful gate.
  Existing vendor Compose mapping warnings remain visible, not suppressed.

### Encoded audio diagnosis (before the audio-import fix)

The Firecrawl developer-index/scrape workflow located and retrieved Google's
[GenAI Speech Recognition audio requirements](https://developers.google.com/ml-kit/genai/speech-recognition/android#audio-input-requirements)
on 2026-09-23. The documented descriptor input is raw, headerless PCM-16, mono,
16 kHz. This matches the installed `AudioSource` bytecode's default format. The
previous `DefaultTranscriptionEngine.transcribeFile` opened the selected URI and
passed its original bytes directly to `AudioSource.fromPfd(..., ONE_SHOT)`.
This was a format mismatch for encoded/container audio. The following pass adds
streaming local decode/conversion; native decoder and ML Kit integration remain
subject to real-device verification.

Fetched evidence is cached under gitignored `.firecrawl/`; no fetched page content
is committed as application code. Firecrawl CLI was unavailable, so the installed
connector's developer search and scrape were used instead.

## Audio-import conversion pass (2026-09-23)

- Added local MediaExtractor/MediaCodec decoding, channel downmixing and
  anti-aliased resampling into headerless little-endian PCM-16/16 kHz/mono.
  Conversion accepts split frames and common integer/float PCM formats, rejects
  malformed/empty input, and emits bounded chunks without an audio disk cache.
- Speech receives a non-blocking pipe in STREAMING mode, paced in 20 ms chunks.
  A slow write cannot trigger a catch-up burst. Completion, failure and
  cancellation stop the producer before closing descriptors; native codec and
  extractor release are in finally blocks. ML Kit collection ends at its completed
  response. Native system calls and vendor lifecycle still need device testing.
- Unreadable audio has a specific user-facing error with an enabled file picker.
  The progress UI explains that file processing proceeds at playback speed.
- Added 22 passing JVM regressions: 11 sample/resampling tests, 7 pacing/failure/
  cancellation/cleanup tests, 3 simulated Android decoder adapter tests and one
  Compose invalid-audio/picker recovery test. Simulated codecs are not evidence
  of physical-device decoding or inference.
- Added 3 compiled, unexecuted Android instrumentation tests for actual WAV
  downmix/resampling, AAC/M4A decoding and cancellation of a full native pipe.
- Full serial gate passed: **337 declared tests, 336 passed, 1 explicitly skipped,
  no failures/errors**; lint **0 errors, 49 warnings**; debug, instrumentation
  and unsigned minified release APKs built. Existing vendor Compose mapping
  warnings remain visible. `git diff --check` passed. No device test execution
  or complete-app verification is claimed.
- Firecrawl's primary-source documentation checks guided the format and stream
  contract: [Google audio requirements](https://developers.google.com/ml-kit/genai/speech-recognition/android#audio-input-requirements),
  [Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec),
  [Android Os](https://developer.android.com/reference/android/system/Os) and
  [Google's speech sample](https://github.com/googlesamples/mlkit/blob/master/android/speech/app/src/main/java/com/google/mlkit/genai/speech/demo/SpeechRecognitionActivity.kt).
  Google's sample supplies live PCM through a pipe using fromPfd's default
  STREAMING mode. These sources support the implementation contract, not a claim
  that imported files have been transcribed successfully on this machine.

## Speech lifecycle failure pass (2026-09-23)

- Reproduced five failures before fixing: synchronous microphone setup, synchronous
  file setup, unexpected stream errors, Stop errors, and a Completed event leaving
  upstream collection active. Six new VM tests now pass, including silent Clear
  cancellation. There are 16 transcription VM tests in total.
- Engine flow creation now happens inside the ViewModel failure boundary. Unexpected
  exceptions become visible errors, captured text survives a stream/Stop failure,
  busy state clears, and a failed file can be retried. Cancellation is rethrown,
  not displayed as an ordinary error. Stop always cancels its captured recording
  in finally; session guards prevent stale work changing a replacement session.
- Completed is terminal at the ViewModel boundary: upstream collection unwinds
  before the normal completion path clears the busy state.
- Extracted the Android-only microphone adapter into PlatformSpeechInput. It uses
  only createOnDeviceSpeechRecognizer, rejects a second concurrent collector,
  and places listener registration/start inside try/finally. Cancellation and
  destroy are attempted independently, including when startup/cancel throws.
  Repeated release is idempotent and ends collection; an old collector's finally
  cannot clear a newer active-session reference. The engine attempts platform
  release even if closing ML Kit clients throws.
- Eleven platform-session tests pass: setup/listener/factory errors and retry,
  cancellation failures, callback output/completion, mapped recognition error,
  duplicate ownership, repeated release, Stop/final result, unsupported-device
  guard and a real Android adapter/Robolectric shadow smoke test. These test
  app-side service ownership; they do not exercise an OEM speech service or mic.
- Targeted gate passed all 27 speech tests. The final full serial gate passed in
  9m 55s: **354 declared tests, 353 passed, 1 explicitly skipped, no failures/errors**;
  lint **0 errors, 49 warnings**; debug, instrumentation and unsigned minified
  release APKs built. Vendor Compose mapping warnings remain visible. No physical
  microphone, AICore inference or Android instrumentation execution is claimed.
- The first full run passed all 354 declared JVM tests except the existing explicit
  skip (353 passed), then lint rejected the Kotlin check-based SDK guard. The
  factory now uses an explicit pre-Android-12 branch; no lint rule was suppressed.

## Speech operation ownership pass (2026-09-23)

- Source review confirmed recording release closed all cached speech clients,
  including clients used by downloads/status checks. Stop also broadcast to every
  cached mode. Advanced microphone startup took the Android fallback whenever its
  second availability check was not AVAILABLE, contradicting the Basic-only policy.
- SpeechClientFactory now creates a distinct SDK client for each operation. Status,
  download and recognition use scoped close, including setup/stream failures and
  cancellation. Download creation is cold and terminal download/recognition events
  end collection. Close failures cannot replace a primary recognition failure.
  SDK text and in-band error response mapping has direct regression coverage.
- The engine owns a cancellable child job for the active recognition. Stop targets
  that session only; Stop during setup cancels setup. Release cancels recognition
  without closing independent download/status clients. Duplicate recognition is
  rejected without releasing the existing owner. Platform fallback is Basic-only
  for unsupported/error status; downloadable modes report installation needed.
- Reproduced rapid Clear/Record starting replacements before an old collector's
  asynchronous cleanup finished. A ViewModel mutex now serializes recognition
  collection, not downloads. Cancelled queued recordings never start; the most
  recent request starts after the prior collection releases its resources.
- Added 20 direct-engine/SDK mapping regressions plus the reproduced VM sequencing
  regression. Targeted gate passed **48 tests, no skips/failures/errors** (20 engine,
  17 VM, 11 Android platform adapter). Native services are replaced with controlled
  fakes/shadows; this is not a device concurrency or inference test.
- Text/image client ownership is unchanged. Native SDK behavior, per-operation
  creation overhead and microphone release must still be verified on hardware.
  The complete serial unit/lint/debug/instrumentation-build/release gate passed in
  **4m 55s: 375 declared tests, 374 passed, 1 explicitly skipped, no failures/errors**.
  Lint reported **0 errors, 49 warnings**; debug, instrumentation and unsigned
  minified release APKs built. Vendor Compose mapping warnings remain visible.
  `git diff --check` passed. No Android instrumentation execution is claimed.
- The first full run passed 374 tests with the one existing skip, then lint required
  a locally visible API-31 guard around AudioSource.fromMic. The explicit guard was
  restored without disabling lint, and the repeat full gate passed.

## Shared Download / Check status / Retry pass (2026-09-23)

- Reproduced six failures before fixing: synchronous client/download setup errors,
  unexpected stream exceptions, empty streams leaving progress stuck, Completed
  not closing its upstream, initial check exceptions and post-download check
  exceptions. All six regressions now pass.
- GenAiFeatureGate catches typed/unexpected failures while preserving cancellation.
  Missing download terminal events become a retryable failure. Completed/Failed
  ends collection before publishing the final state; post-download checks run
  separately, so failed checks preserve completion and Retry checks status only.
- Availability checks coalesce and show a checking state. A post-download check
  supersedes an older check but waits for its cancellation cleanup. A finished
  check with no result exposes Retry instead of an indefinite loading screen.
- Check status can cancel a stale local request if the model is now AVAILABLE or
  UNSUPPORTED; identity checks protect newer downloads. A failed check preserves
  active download progress and displays a visible error plus the existing working
  Check status button. Releasing the controller cancels its jobs, is idempotent,
  ignores subsequent taps and does not throw a platform close error through disposal.
- Prompt SDK client creation now occurs on collection inside the mapped error
  boundary, on the engine IO dispatcher. Factory errors become download failures;
  cancellation and consumer exceptions remain exceptions, not false download errors.
- Added 23 regressions: 16 controller tests, 2 gate-state tests, 1 Compose progress/
  error/button test and 4 cold-download-boundary tests. Targeted verification passed
  **50 tests, no failures/errors/skips**. Actual model installation and callback
  ordering still require Android/AICore verification.
- Checked Google's official [DownloadCallback reference](https://developers.google.com/android/reference/com/google/mlkit/genai/common/DownloadCallback)
  and [Summarizer reference](https://developers.google.com/android/reference/com/google/mlkit/genai/summarization/Summarizer)
  through Firecrawl. They document completion/failure callbacks and a download
  future that completes when download finishes or fails (immediately when already
  downloaded). This supports terminal-event handling; it does not verify native
  callback ordering or cancellation behavior. Source snapshots are saved locally
  in `.firecrawl/google-download-callback.md` and
  `.firecrawl/google-summarizer-reference.md` (gitignored).
- Full serial unit/lint/debug/instrumentation-build/release verification passed in
  **4m 53s: 398 declared tests, 397 passed, 1 explicitly skipped, no failures/errors**.
  Lint reported **0 errors, 49 warnings**. Debug, instrumentation and unsigned
  minified release APKs built. Existing vendor Compose mapping and deprecated
  instrumentation test-rule warnings remain visible. `git diff --check` passed.
  No device instrumentation or real model installation was executed.

## Transcription handoff and active-control pass (2026-09-23)

- Reproduced an older queued shared file automatically overwriting a newer
  recording when the user switched to a file-capable mode. Starting an accepted
  recording now consumes the old pending source; denied/disabled Record attempts
  do not. Accepting a new file clears older pending/retry sources before either
  starting it or waiting for availability. Clear still discards queued input.
- Reproduced availability Retry endlessly requeueing the previous file while
  leaving status ERROR. A newer queued selection could also be displaced by that
  older file. Retry now checks availability first for failed/unfinished checks;
  pending input keeps precedence. Retrying an actual file failure still reopens
  that file when the recognizer is ready.
- Reproduced a slow successful availability check erasing a newer recording
  failure. Refresh only clears the error it started with; a later recognition or
  permission failure is preserved, including when the check itself throws.
- Compose regressions reproduced Stop hidden by a background availability error
  and Cancel hidden by stale download failure during file transcription. Active
  operations now render their Stop/Cancel action before availability/download
  panels. Busy recognition does not expose a Retry button whose handler is a no-op.
- Added 8 regressions (6 ViewModel, 2 real Compose semantics interactions).
  Focused verification passed **49 tests with 1 existing explicit skip**: 23
  transcription VM, 3 transcription UI, 13 shared feature controls and 11 option
  controls declared. Native mic and provider behavior are not covered by these
  controlled engine/UI tests.
- The complete serial unit/lint/debug/instrumentation-build/release gate passed
  in **4m 39s: 406 declared tests, 405 passed, 1 explicitly skipped, no failures
  or errors**. Lint reported **0 errors, 49 warnings**. Debug, instrumentation and
  unsigned minified release APK builds succeeded; vendor Compose mapping warnings
  remain visible. `git diff --check` passed. Device instrumentation was not run.

## Text/image client ownership pass (2026-09-24)

- Source review confirmed option changes closed the previous cached client while
  an existing operation could still reference it. Two real engine/provider tests
  reproduced Image Question release closing an active Ask client, and release
  closing a client used by both generation and download.
- Added a synchronized option-keyed lease cache. Each SDK operation holds its lease
  through completion/cancellation cleanup. Replacing or releasing a cached client
  retires it; it closes only after the last active lease is returned. Same-option
  idle clients remain reusable. Retired or failed-close handles are never reused.
  All text/image inference, download and capability paths use this scope.
- Cache tests cover key replacement, warm reuse, multiple owners, duplicate close,
  cancellation, factory/cleanup failure and 50 threaded clear/release interleavings.
  A cleanup exception is suppressed behind an existing operation failure. Provider
  disposal attempts each cache independently and does not throw native cleanup
  failures through ViewModel disposal; this does not guarantee native close succeeds.
- Direct capability/Prompt tests also reproduced explicit SDK cancellation being
  mapped to an AI failure or swallowed as a successful model-name lookup. Both
  now propagate cancellation while releasing the operation's lease. Ordinary
  optional model-name failures still leave the name absent.
- Added 14 tests: 9 cache and 5 provider/engine/capability integrations. Focused
  verification passed **36 tests, no failures/errors/skips**, including the existing
  download boundary and shared feature gate cases.
- Tests use a controlled Prompt SDK interface, not AICore. An attempted native
  Summarizer shadow failed inside Robolectric's bytecode instrumenter before its
  assertions, and the native ImagePart class hit a JVM VerifyError. Those attempts
  were replaced with explicit app-cache tests and Prompt interface integration;
  neither failure is evidence of Android runtime failure or successful native
  feature verification. No verifier suppression or new ignored test was added.
- README and the engine release contract now describe lease retirement rather than
  implying that disposing one screen can immediately close another owner's client.
  Native AICore inference/concurrency and device lifecycle remain pending.
- The complete serial unit/lint/debug/instrumentation-build/release gate passed in
  **5m 3s: 420 declared tests, 419 passed, 1 existing explicit skip, no failures or
  errors**. Lint reported **0 errors, 49 warnings**. Debug, instrumentation and
  unsigned minified release APK builds succeeded. Vendor Compose mapping warnings
  remain visible. `git diff --check` passed; no device instrumentation was executed.

## Capability freshness and Refresh ordering pass (2026-09-24)

- Extracted the existing snapshot coordinator into CapabilitySnapshotCache, used
  directly by DefaultDeviceAiCapabilityManager. Vendor probes remain in the
  manager; tests can now control cache clocks and probe completion independently.
- Reproduced eight cache failures before fixing: a single-task result skipping
  the first full check; task checks extending stale results for unrelated tasks;
  wall-clock forward/backward adjustments corrupting expiry; older task results
  overwriting newer task/full checks; cancelled queued work starting an unnecessary
  probe; and a cancelled full check treating a prior partial snapshot as complete.
- A separate full-check completion time uses elapsed realtime, including device
  sleep. Wall-clock timestamps remain display metadata. Only a successfully
  completed full check renews its one-minute cache. Forced Refresh bypasses it.
- Single-task resolution now happens under the same mutex as publication and full
  checks. A stale probe cannot finish out of order and undo a newer model status;
  a cancelled queued request never calls its vendor probe.
- Added 11 coordinator tests for partial/full freshness, expiry boundaries, clock
  changes, ordering, cancellation, failure/Retry and overlapping cached checks.
  Added a Home integration test using the actual coordinator: opening Home fills
  missing tool statuses and pressing Refresh obtains new results despite freshness.
- Focused verification passed **39 tests with no failures/errors/skips** (11 cache,
  5 SDK-interface ownership, 18 shared gate, 2 Device AI VM and 3 Home VM tests).
  The first failure/Retry assertion incorrectly required exception object identity
  across coroutine stack-trace recovery; it now checks type and message. The
  repeat baseline had 8 genuine failing cache tests before the production fix.
- Full serial unit/lint/debug/instrumentation-build/release verification passed in
  **4m 55s: 432 declared tests, 431 passed, 1 existing explicit skip, no failures or
  errors**. Lint reported **0 errors, 49 warnings**. Debug, instrumentation and
  unsigned minified release APK builds succeeded. Vendor Compose mapping warnings
  remain visible. Native provider checks and device lifecycle are not proven by
  the injected clock/probe tests; no device instrumentation was executed.

## Saved-result deletion synchronization pass (2026-09-24)

- Two new Developer regressions failed before the fix: deleting a saved row or
  clearing history left the retained result marked Saved and blocked another Save.
- The shared Save controller now watches the returned history row ID. Confirmed
  deletion resets the current result's saved flag; no inference rerun is needed.
  All nine tools consume both saved and unsaved updates. Ask supplies independent
  message slots so deleting one answer does not reset another answer.
- A replacement save cancels the previous row observer for its slot. Current-result
  guards reject late deletion updates, stale slots are pruned on another Save, and
  ViewModel disposal cancels all remaining observers. Temporary read failures keep
  the confirmed save and retry observation after five seconds; a read error alone
  is not treated as deletion.
- Added 16 regressions: six controller cases (unrelated row deletion, deletion
  before first observation, stale-result isolation, replacement-slot isolation,
  read recovery and disposal); eight tool tests covering all nine tools; one
  Compose button-state test; and one real in-memory Room test exercising both
  single deletion and Clear. Existing Save deduplication/disabled/failure tests
  remain in place. Focused tool/controller/UI and real Room runs passed.
- The full serial unit/lint/debug/instrumentation-build/release gate passed in
  **4m 55s: 448 declared tests, 447 passed, 1 existing explicit skip, no failures
  or errors**. Lint reported **0 errors, 51 warnings**; debug, instrumentation and
  unsigned minified release builds succeeded. Vendor Compose mapping warnings
  remain visible. Native inference, Android navigation/process restoration and
  device controls remain outside what these automated tests prove.

## Independent identical-answer Save pass (2026-09-24)

- Two further regressions reproduced rapid Save on two distinct Ask answers with
  identical prompt/output dropping the second request. Pending-save deduplication
  used only content, although the UI exposes an independent Save for each answer.
- The pending key now includes the result slot (Ask message ID). Repeat taps on the
  same answer still deduplicate across timestamp changes; distinct answers can each
  save and independently observe deletion, even when their text matches.
- Added a controlled suspended-write controller test and a real Ask ViewModel test
  covering both Save taps and deletion of only the first answer's history row.
  Focused verification passed **17 tests with no failures/errors/skips**. The final
  full serial unit/lint/debug/instrumentation-build/release gate passed in
  **3m 49s: 450 declared tests, 449 passed, 1 existing explicit skip, no failures
  or errors**. Lint reported **0 errors, 51 warnings**. All three APK builds
  succeeded, including the unsigned minified release. Vendor Compose mapping
  warnings remain visible. No device instrumentation was executed.

## Cross-screen preference and reset lifetime pass (2026-09-24)

- Four controlled regressions failed before the fix: leaving Settings cancelled an
  accepted theme change; leaving Translate cancelled queued language selections;
  leaving Settings interrupted a confirmed Clear all; and an older language write
  waiting in a screen-local mutex restored preferences after another screen's reset.
  The reset reproduction included storage-level serialization, modelling DataStore.
- Added singleton SettingsActions, using the existing application SupervisorJob
  scope. Each button accepts its operation immediately into one FIFO mutex queue.
  Settings, Translate and Onboarding await outcomes in their own ViewModel scopes:
  navigating away cancels feedback/navigation, not accepted persistence work.
  Removed the competing Settings/Translate screen-local mutation queues.
- Clear all holds its queue position across history deletion and preference reset;
  a later selection waits for both stages and then remains stored. Failures propagate
  to existing Retry/error feedback without cancelling subsequent queued operations.
  Application-scope cancellation cancels pending actions and rejects new work.
- Added 12 tests: five lifecycle/reset integrations, six queue tests, and one
  onboarding disposal test. All **38 focused tests passed with no skips/failures/
  errors**, including existing settings and translation error/retry regressions.
  Two initial queue failure tests used TestScope's special reporting background job,
  which recorded expected async exceptions even after await handled them. Their
  fixture now uses the production-equivalent SupervisorJob, still parented to test
  cleanup; no production exception suppression or ignored test was added.
- This is process-lifetime ownership, not persistence across an OS kill. Room and
  DataStore are still separate stores: a failed second reset stage reports failure,
  may leave history already deleted, and can be retried. It is not an atomic reset.
- Full serial unit/lint/debug/instrumentation-build/release verification passed in
  **4m 13s: 462 declared tests, 461 passed, 1 existing explicit skip, no failures
  or errors**. Lint reported **0 errors, 51 warnings**. Debug, instrumentation and
  unsigned minified release APK builds succeeded. Vendor Compose mapping warnings
  remain visible. No device instrumentation was executed; process-death and native
  inference/control verification remain pending.

## Speech Download, Check status and queued-audio recovery pass (2026-09-24)

- Reproduced six controller failures: post-download capability refresh was labelled
  a download failure; Completed/Failed streams could remain collected; empty streams
  left eternal progress; an authoritative ready check could not recover a lost
  completion callback; and progress dismissed newer microphone-permission errors.
  Two Compose regressions separately reproduced Check status hidden during active
  downloads. A strengthened stale-check test also reproduced queued audio starting
  from an older ready result while a newer download remained active.
- Terminal download events now stop collection, wait for upstream cleanup, and
  only then publish completion/failure. Empty flows become retryable failures.
  Duplicate Download taps cannot overlap an attempt still finishing cleanup.
- Capability refresh after a download has its own mode-specific failure and job.
  Its Check status retry does not repeat a completed download. Mode switches retain
  the right check error, repeated retries coalesce, and progress no longer clears
  another operation's error.
- Availability checks capture the attempt they observed. A ready/unsupported result
  can cancel and await that attempt's cleanup, then reconcile progress; an older
  check cannot cancel a newer attempt. Recording/file start eligibility also honors
  active or unresolved failed downloads, so stale readiness cannot start queued audio.
- Check status remains visible during local or provider-reported download progress.
  Active Record/Cancel controls retain priority. A completed download with only a
  capability-check error offers Check status, not another Download.
- Added 13 tests: nine controller integrations and four Compose recovery-button
  cases. Focused verification passed **40 tests with no failures/errors/skips**,
  including the existing 24 transcription VM and three prior audio/active-control
  UI cases. These use controlled engine/check streams, not native AICore.
- Full serial unit/lint/debug/instrumentation-build/release verification passed in
  **4m 20s: 475 declared tests, 474 passed, 1 existing explicit skip, no failures
  or errors**. Lint reported **0 errors, 51 warnings**. Debug, instrumentation and
  unsigned minified release APK builds succeeded. Vendor Compose mapping warnings
  remain visible. No device instrumentation or native speech verification was run.

## Text/image request setup and recovery pass (2026-09-24)

- Source review found both Prompt paths constructing SDK requests before entering
  the mapped flow. Two regression assertions failed before the fix: request setup
  ran eagerly, and a controlled request-building exception escaped as a raw
  GenAiException instead of the AiException handled by the screens.
- Both text and image paths now pass a request factory to the existing prompt flow.
  It builds on collection, on the IO dispatcher, before acquiring a client and within
  the existing mapping boundary. No new production test seam or SDK dependency was
  introduced. Ordinary coroutine cancellation and collector exceptions still escape
  without being presented as inference errors.
- Eight tests added: four real-engine/provider request-boundary cases, Ask recovery,
  Image recovery, both developer explainers' corrected-input rerun, and a Compose
  image Retry/privacy case. All **48 focused tests passed without failures/errors/
  skips**. The request-building fault is injected through controlled history traversal;
  it proves the app boundary, not native SDK validation or input-limit behavior.
- An initial downstream exception identity assertion also failed because coroutine
  stack-trace recovery copied the same exception type/message across flowOn. That
  fixture now checks type/message, not object identity; no production suppression
  or skipped test was added for this runtime behavior.
- Full serial unit/lint/debug/instrumentation-build/release verification passed in
  **3m 33s: 483 declared tests, 482 passed, 1 existing explicit skip, no failures
  or errors**. Lint reports **0 errors, 51 warnings**; all three APKs built.
  Vendor Compose mapping warnings remain visible. Native AI, input limits,
  picker/microphone interactions, accessibility and OS lifecycle remain pending.
- A fresh environment check found API 35/36 system images and runnable AVDs now
  installed, unlike the initial audit check. No phone was attached. A headless,
  read-only API 36 session ran the existing Android instrumentation suite; the saved
  virtual device does not retain test installs/data. This does not
  provide supported-hardware Gemini Nano inference evidence.

## First executed Android instrumentation pass (2026-09-24)

- Revalidated the environment instead of relying on the initial no-emulator finding:
  Windows Hypervisor Platform is available and `medium_phone` uses an installed
  API 36 x86_64 image. Started it headlessly with `-read-only -no-snapshot`, using
  serial `emulator-5580`; no personal virtual-device state was deliberately changed.
- Initial run: **37 executed, 26 passed, 11 failed, none skipped**. Failures were
  test-selector/layout assumptions: a search query matched the language result and
  editable field; Home tests looked up unloaded lazy items and hidden merged text
  instead of the chip's descriptive accessibility node; History used text for its
  delete icon and confused the Summary filter with a result badge.
- Corrected list-level scrolling, exact semantic selectors and icon-versus-dialog
  targeting. Home still checks both the visible status text (unmerged tree) and its
  accessible description. No production UI change or removed assertion was needed.
  The second run had one remaining outdated `Swap` label; updated it to the actual
  `Swap languages` label and retained the same-language disabled-action assertion.
- Final `connectedDebugAndroidTest --no-parallel --max-workers=1 --console=plain`
  **passed in 1m 13s: 37 tests, 0 failures, 0 errors, 0 skips**. XML:
  `app/build/outputs/androidTest-results/connected/debug/TEST-medium_phone(AVD) - 16.xml`.
  Coverage: 9 Home, 8 History, 8 shared gate, 8 route/handoff contract, 1 language
  picker flow and 3 native audio tests. WAV decoding/downmix, AAC encoder/container/
  decoder integration and cancellation of a full unread native pipe all executed.
- This is Android-framework evidence for those specific cases, not a complete app
  end-to-end run. The UI tests render controlled content states. Real MainActivity
  recreation/pending-share URI grants, system pickers, physical microphone behavior,
  native AICore/translation/OCR inference and broader accessibility remain unverified.
  Those are the next integration priorities; do not label the full goal complete.
- Final serial unit/lint/debug/instrumentation-build/release gate after the test
  corrections passed in **1m 19s: 483 declared JVM tests, 482 passed, 1 existing
  explicit skip, 0 failures/errors; lint 0 errors, 51 warnings**. All APK targets
  passed. The native result above remains a separate 37/37 executed-test result,
  not part of the JVM total. `git diff --check` passed. The temporary emulator was
   shut down with `adb -s emulator-5580 emu kill`; no saved AVD was deleted or wiped.

## Share recreation regression completion — 2026-09-25

- Real MainActivity instrumentation reproduced `Cannot navigate to share. Navigation
  graph has not been set`. The share effect could run before Scaffold installed the
  NavHost during restoration. It now waits for `currentBackStackEntryFlow.first()`
  and is keyed by controller and pending state; clearing a share cancels the wait.
  No arbitrary delay or exception suppression was added.
- Two deterministic delayed-graph JVM cases failed before the fix; all four focused
  share-navigation cases pass afterward, including cancellation and a single router.
- Four real-activity tests cover developer input/result retention, history search
  across tabs/recreation, warm share Back/Cancel preserving the existing draft, and
  cold/latest shares surviving launcher intents/recreation before one-time routing.
  The warm test observes the real resumed activity because ActivityScenario's launch
  intent filtering stops following MAIN-to-SEND changes. Production intents are not
  modified by the test. These cases cover recreation, not OS process death.
- Full serial JVM/lint/debug/instrumentation-build/release gate passed in **6m 32s**:
  **485 declared, 484 passed, 1 existing skip, 0 failures/errors; lint 0 errors and
  51 warnings**. Existing vendor Compose mapping warnings remain visible.
- Full `connectedDebugAndroidTest` passed in **2m 11s** on read-only API 36
  `medium_phone`: **41 tests, 0 failures/errors/skips**. The formerly crashing warm
  share test passed. XML is under `app/build/outputs/androidTest-results/connected/debug/`.
- Still unverified: real AICore/translation/OCR inference, microphone and system
  picker grants, full OS process-death recovery, OEM behavior and broad accessibility.
  Do not interpret a passing automated suite as release certification on hardware.

## Final local completion pass — 2026-09-25

- Found a second, real process-death defect: after receiving a newer share, Android
  relaunched MainActivity with the original task intent and replayed the older text.
  The host script verified the PID changed and the rendered router showed the wrong
  content before the fix. Activity saved state now holds only the normalized pending
  text or URI; arbitrary sender extras/file bytes are not copied. Consumed/dismissed
  shares are excluded. Three parcel-roundtrip JVM regressions cover latest text,
  image/audio URI types, and missing/invalid state.
- `scripts/verify-process-restoration.ps1 -Serial emulator-5580` passed against the
  fixed debug APK: two genuine background process kills/restarts, latest pending share
  retained and no replay after dismissal. This is not a force-stop/reinstall or a
  recreation-only simulation. Unsaved tool drafts/handoffs remain memory-only; this
  check does not claim universal process restoration or persisted URI grants.
- Native bundled ML Kit checks passed: readable image text, blank-image/no-text, and
  French language identification/blank-input handling. These use real SDK engines,
  not mocks, and request no model download. They do not cover Gemini Nano, translated
  output, or physical camera/microphone behavior.
- Photo and audio system-picker open/Back cancellation checks passed separately.
  The harness verifies the actual external picker window and return to MindKit with
  no selection callback. Initial failures were harness package-query visibility and
  modular photo-picker delegation; the test shell resolves the installed handler.
  No production permission/query change or relaxed app assertion was needed.
- Removed 34 lint-confirmed unused string resources and 9 redundant empty superclass
  calls, removed the duplicate activity label, corrected two count labels with real
  plurals, and relocated adaptive icons to the minimum-SDK-appropriate directory.
  The empty old directory was removed; icon content is preserved. Source changes are
  reviewable/recoverable through Git. Both debug/release resource merge outputs needed
  regeneration after the move; targeted resource-link tasks then passed.
- Lint is **0 errors, 4 warnings**, down from 51. The remaining four are update
  advisories for Gradle, AndroidX Core, Activity and Navigation. No suppression or
  blind dependency upgrades were used. The JVM dialog skip remains visible because
  of the documented Robolectric limitation; its real-Android equivalent passes.
- Remaining validation requires a supported-device session: Gemini Nano text/image/
  speech success paths, live microphone behavior, translation model operations and
  output, successful photo/audio provider selection and URI access across lifecycle,
  OEM differences and accessibility review. No physical phone was connected in this
  pass. The user was asked to connect one; hardware success is not inferred from mocks.
- Final combined gate completed successfully in **4m 40s**: `testDebugUnitTest
  lintDebug connectedDebugAndroidTest assembleDebug assembleDebugAndroidTest
  assembleRelease --no-parallel --max-workers=1 --console=plain`. JVM XML:
  **488 declared, 487 passed, 1 explicit skip, 0 failures/errors**. Android XML:
  **46 passed, 0 failures/errors/skips**. Lint: **0 errors, 4 update advisories**.
  Debug, instrumentation and unsigned minified release APKs built. Vendor OSS license
  Compose mapping warnings remain visible and are separate from Android lint.
  The read-only emulator was shut down, with no saved AVD deleted or wiped.
