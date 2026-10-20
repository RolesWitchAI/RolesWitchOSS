# AGENTS.md

Kotlin Multiplatform app: Android-first chat assistant (`:androidApp`) over a
shared KMP core (`:sharedLogic`), plus an iOS app (`iosApp/`) that mirrors the
Android feature surface. `README.md` covers
build/test commands, prerequisites, and the iOS arch gotcha — read it first.
This file captures only what the README gets wrong, omits, or what an agent
would otherwise misread from the tree.

## Environment (set up before running any Gradle/xcodebuild command)

- **JDK 21 is required for the Gradle daemon**
  (`gradle/gradle-daemon-jvm.properties`, `toolchainVersion=21`). On macOS there
  is no `java` on `PATH` by default — **every Gradle invocation in this repo
  must be preceded by the export below**, or it will fail with
  `Unable to locate a Java Runtime`. The Xcode run-script phase already does
  this; command-line Gradle does not.
  ```sh
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ```
  When invoking Gradle via Bash here, prefix the command with the export, e.g.
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :androidApp:assembleDebug`.
- `local.properties` (gitignored) must contain `sdk.dir=...` for Android builds.
- **`DEVELOPER_DIR` must be set for `xcodebuild`.** If `xcode-select -p` points
  at `/Library/Developer/CommandLineTools` (which happens if Command Line
  Tools were installed or selected without picking Xcode), bare
  `xcodebuild` fails with `requires Xcode, but active developer directory … is
  a command line tools instance`. Export the Xcode app path:
  ```sh
  export DEVELOPER_DIR="/Applications/Xcode.app/Contents/Developer"
  ```
  Prefix every `xcodebuild` invocation with this export, e.g.
  `export DEVELOPER_DIR="/Applications/Xcode.app/Contents/Developer" && xcodebuild …`.
- `org.gradle.configuration-cache=true` is enabled in `gradle.properties`.
  Tasks that mutate external state (signing, native toolchain invocation) may
  need `--no-configuration-cache` if they fail with a config-cache error.
- The release signing config in `androidApp/build.gradle.kts` resolves its
  credentials through `findProperty` (`roleswitchStoreFile` /
  `roleswitchStorePassword` / `roleswitchKeyAlias` / `roleswitchKeyPassword`,
  defined in `~/.gradle/gradle.properties` — never in the repo) and the
  keystore itself lives outside the repo tree. A fresh clone builds the
  release APK/AAB **unsigned**; do not move secrets into the repo.

## Module boundaries

- Gradle modules (`settings.gradle.kts`): exactly two — `:androidApp` (phone)
  and `:sharedLogic` (the KMP core shared with iOS). `iosApp/` is a plain Xcode
  project — not a Gradle module — and consumes the `SharedLogic` XCFramework
  produced by `:sharedLogic:link*FrameworkIos*`.
- **`:sharedLogic` is the iOS-sharing vehicle.** Anything put there must compile
  for iOS, so Android-only code (platform APIs, Compose, Android libraries) goes
  in `:androidApp` instead. There is no third module to reach for: the former
  `:sharedCore`/`:sharedUi` pair existed only so the phone and the Wear OS
  companion could share code, and both were deleted with the watch.
- **The package name `ai.roleswitch.android.*` is NOT a module boundary.** Most
  of the shared core (data, services, settings, app container) lives under
  `ai.roleswitch.android.*` inside `sharedLogic/src/commonMain`. Treat anything
  in `sharedLogic/src/commonMain` as shared code regardless of package name.
- Android-only services (STT/TTS, Vosk, CameraX, ML Kit, LiteRT-LM, the Compose
  UI) live in `androidApp/src/main/kotlin/ai/roleswitch/android/`. The shared
  service layer (`OAIService`, `LLMService`, `InferenceEngine`, `SpeechService`
  interface) lives in `sharedLogic/.../commonMain/.../service/`.

## Inference engines

There are two engines, chosen per thread by `EffectiveSettings.useOnDevice`:

- **Remote** — `OAIService` (commonMain), any OpenAI-compatible endpoint. Default, both platforms.
- **On device, Android** — `LiteRtService` (`:androidApp`), Google LiteRT-LM
  (`com.google.ai.edge.litertlm`). **Gated to Android 12+ (API 31+)** by
  `onDeviceInferenceSupported` (`LiteRtService.kt`): below it the settings picker hides
  the option entirely and a stored/onboarded `useOnDevice` choice falls back to remote
  (`RolesWitchApp` passes `onDeviceEngine = null`), because 2018-2020-era hardware
  cannot be assumed to run a model. Keep every gate on that predicate, not on
  `minSdk` — the floor is 29 for the app while on-device inference stays 31+.
- **On device, iOS** — `LiteRtService` (`iosApp/iosApp/LiteRT/`), the native `LiteRTLM`
  Swift package (`exactVersion 0.18.0`). See [On-device inference on iOS](#on-device-inference-on-ios-litert).

All three implement `InferenceEngine` (commonMain). **That whole seam is deliberately
`public`, not `internal`** — `:androidApp` is a separate Gradle module, so
`internal` declarations in `:sharedLogic` are invisible to it and an Android-only
implementation could not exist otherwise. It is also what lets the iOS engine be
written in **Swift**: the protocol exports to ObjC, and a Swift class conforms to it
directly and is handed back through `createAppContainer(onDeviceEngine:)` — the same
trick `ClosureFlowCollector` uses for `FlowCollector`. There is no Kotlin-side adapter.
`LLMService` takes the on-device engine as a nullable *last* constructor parameter with
a default, so a platform without one compiles unchanged.

**`useOnDevice` is engine-agnostic; its storage is not.** The Kotlin property is
`useOnDevice`, but the preference key is still `litertEnabled` and the thread column
still `customUseLiteRt` — both predate the second engine, and renaming storage would
reset the engine choice on every device that has already made one. Do not "finish" the
rename by touching the key or the column.

`LLMService` owns everything around a turn (history trimming, MCP session,
persistence, streaming flows) and delegates only generation, so nothing else in
the app knows which engine ran. Things an agent will otherwise get wrong:

- **`EffectiveSettings.visionAvailable`, not `enableVision`, decides whether
  images may be sent.** On device the answer comes from the checkpoint, not a
  preference — `LitertModel.supportsImage` for LiteRT — so the `Enable vision`
  toggle is hidden there. The flag can only turn vision *on*, because a tower's
  absence is unknowable until one is built; a text-only checkpoint therefore
  refuses images with the preference on. Every consumer (the request builder, the
  chat attach button, the video-call button) reads `visionAvailable` — reading
  `enableVision` instead is a bug on both platforms, and on iOS it was one twice
  over, since the global was read where the thread's value was meant.
- **A `Conversation` is rebuilt every turn** from `consolidateMessages`' trimmed
  history via `ConversationConfig.initialMessages`. LiteRT's conversation is
  stateful and the rest of the app is not (messages get edited, deleted, or fall
  outside the limit). The `Engine` — the multi-gigabyte part — is *not* rebuilt;
  it is keyed by model/accelerator/maxTokens/vision and released only on those
  changes. **Nothing releases it on backgrounding or on a system memory
  callback** — there is no `onTrimMemory` override, deliberately: the levels are
  not a severity ordering, `TRIM_MEMORY_UI_HIDDEN` (20) is pure visibility and
  fires on every trip to the camera or a permission dialog, and every level that
  *is* pressure carries "apps are not notified of this level since API level 34"
  (as does `onLowMemory`). Narrowing the test would have meant one behaviour on
  API 31–33 and another on 34+; Google's AI Edge Gallery overrides neither
  callback on this same runtime and lets the process be killed instead, and so
  does this app. History replay is **text-only**, which is safe only
  because `IMAGE_WINDOW_SIZE = 1` already strips images from all but the newest
  message.
- **MCP tools reach the on-device model on Android only.** LiteRT (`:androidApp`)
  declares each MCP function as its own declarative `OpenApiTool` (`McpLiteRtTools`, one
  `ToolProvider` per `McpToolSpec`, registered under the real function name so no name
  conversion applies), and the runtime's *automatic tool calling* runs the call loop
  internally — there is **no** `run_mcp_tool` meta-dispatcher and **no** tool catalogue
  appended to the system prompt. iOS's LiteRT engine is **not** wired to MCP:
  `LiteRtInferenceEngine` accepts `toolSpecs`/`callTool` and ignores them, the
  conversation is built with `tools: []`, and both settings screens hide the Tools
  section while the effective engine is on device.
- **`_streamingResponse` must be updated with `update { }`, never `value +=`** —
  on-device chunks arrive on a LiteRT native thread.
- `messageLimit` is capped at `ON_DEVICE_MESSAGE_LIMIT` (120) for on-device turns;
  "Unlimited" (100000) is hidden in the "Messages to process" picker while the
  engine is on device, and switching engines (Settings, or a thread's Model
  settings) resets the limit to 50 — the shared default both pickers can show.
- The vision backend is pinned to the model's own `visionAccelerator` (GPU for
  the Gemma family), *not* the user's accelerator choice — CPU vision produces
  garbage rather than a slower answer. Only the text backend is user-selectable.

Models are a **static registry** (`LitertModels`, commonMain — it must be there
because `EffectiveSettings` reads `supportsImage`), mirroring `VoskModels`. Only
checkpoints that download from HuggingFace **without auth** belong in it; the
Gemma 3n weights are gated (HTTP 401) and are excluded until the app grows a
HuggingFace sign-in. Files live at `filesDir/litert/<id>/<commitHash>/`, a
directory per model because `EngineConfig.cacheDir` is null and LiteRT writes its
compiled cache next to the weights — so deleting a model means
`deleteRecursively()`.

## Local network permission (Android 17)

- **targetSdk 37 + Android 17 → `ACCESS_LOCAL_NETWORK` is a runtime permission.** Local
  Network Protection is mandatory for apps targeting SDK 37: direct sockets to LAN
  addresses are silently dropped — the app sees a plain connect *timeout*, not an
  error, indistinguishable from a dead server. The inference endpoint, MCP tools and the
  external speech server are usually exactly such LAN hosts (LM Studio / Ollama), so the
  permission is load-bearing. It is declared in `AndroidManifest.xml` and gated by
  `rememberLocalNetworkGate` (`ui/components/Permissions.kt`): the system prompt shows
  at most once per install — asked-flag in plain `SharedPreferences`, so Settings →
  Reset cannot re-arm it — at the flows that actually reach a user-supplied server:
  opening the model picker (fetchModels), the external-voice Save preview, and the
  speech-server Save, which fires a light /v1/models probe whose result is ignored —
  a user who only enters the address and never touches the voice picker would
  otherwise first knock on the server from a call's first reply. After
  that a missing grant surfaces only as the in-app `PermissionDeniedDialog` with the
  jump to the app's system settings page. Deliberately not at app startup and not on
  call entry: both would nag for a grant a remote-only setup never needs. **Do not
  remove the declaration or the gate** — without the grant no local server is
  reachable. Chrome works without a grant (browser exemption), which makes "works in
  Chrome, times out in the app" the signature of a missing grant.
- **An installed build that doesn't declare the permission cannot be unblocked from
  adb**: `pm grant` silently no-ops (not in the manifest), and the uid-level
  `ACCESS_LOCAL_NETWORK` appop stays `ignore` even against `cmd appops set --uid`.
  To reproduce at the uid level: `adb shell run-as ai.roleswitch.android curl …` times
  out while the same `curl` under `adb shell` succeeds.

## Live2D characters (Android only)

Ported back from the old `RolesWitchAndroid` repo. A thread with a non-null
`MessageThread.cubismId` renders a bundled Cubism model on the call screen instead of
the avatar photo; null keeps the photo. Chosen from the Profile screen's avatar sheet
("Use Character" → `CharacterPickerScreen`): characters are swiped through left/right
and committed with the top-bar check (persisting then, like Profile's Save — back
discards). Turning a character *off* lives in the Profile screen's avatar sheet
("Disable Character", offered only while a character is set — it nulls `cubismId` and
the photo underneath returns; the picker itself has no "use photo" exit). There **is**
no
long-tap model cycling in the call any more — the character is picked once, on the
Profile screen. The picker keeps **one** GL surface behind a transparent pager — the
native manager cannot hold more than one model, so per-page `Live2DView`s are not a
thing; settling on a page switches the model in place.

- **Three lists must stay index-aligned, and the order *is* the whole mapping:**
  `Live2dCharacters.all` and `modelMotionConfigs` (both in `ui/components/Live2DView.kt`)
  and the C `ModelDir[]` in `androidApp/src/main/cpp/cubism/LAppDefine.cpp`. The native
  side only knows indices; a stored `cubismId` means nothing without this order.
  `Live2dCharacters.indexOf` returns -1 for an unknown id, and every consumer treats -1
  as "no character" (photo) — keep that fallback, it is what makes a registry change or
  a stale id harmless.
- **Mouth control is the native side's, and it depends on `_lipSyncIds`.** Some models'
  motions carry mouth curves of their own — all of Hibiki's do, *Idle* included — which
  reads as the character "talking while idle" unless something masks it. `Update()` in
  `LAppMinimumModel.cpp` does: it forces every `_lipSyncIds` parameter to 0 while not
  speaking and adds `_lipSyncValue` while speaking. That only works because
  `SetupModel()` fills `_lipSyncIds` by scanning the model's own parameters for
  "MOUTH_OPEN", matched **case- and underscore-insensitively** — hibiki/haru write
  `PARAM_MOUTH_OPEN_Y`, hiyori writes `ParamMouthOpenY`, and the model3.json
  `LipSync` group can't be the source (hibiki's json has none). The vector was
  originally never populated at all, which made both halves of that block no-ops;
  a later underscore-respecting match quietly re-broke hiyori. On the Kotlin side the lip-sync loop
  resets `_lipSyncValue` in a `finally` — the loop is *cancelled* when speaking flips,
  so a reset placed after it would never run and the mouth would stay open after a
  reply.
- **The native manager is a process-wide singleton holding one model.** Exactly one
  `Live2DView` may be alive at a time (Navigation Compose guarantees this today — only
  the top destination composes). Model switches must be queued to the GL thread via
  `glSurfaceView.queueEvent { CubismBridge.nativeSetModelIndex(idx) }` — loading off the
  GL thread races `OnUpdate`. `SwitchModel` no-ops when the index already matches, which
  is what makes "seed the initial index *and* queue the switch" safe in `Live2DView`.
- **Nothing may build the manager before `onSurfaceCreated` runs.** A queued runnable
  can execute on the GL thread *before* the renderer's `onSurfaceCreated`, and the
  manager's constructor allocates through the framework allocator that
  `CubismFramework::StartUp` only registers inside `OnSurfaceCreate` — building it
  earlier died on-device as a SIGSEGV in `operator new(CubismAllocationTag)`. Two
  guards, both required to stay: `Live2DView` keys its switch/motion/lip-sync effects on
  a `surfaceGeneration` counter bumped in `onSurfaceCreated`, and the JNI entry points
  in `CubismBridge.cpp` (`nativeSetModelIndex`/`nativeStartMotion`/
  `nativeSetLipSyncValue`/`nativeSwitchModel`/`nativeGetModelIndex`) check
  `CubismFramework::IsStarted()` — staging only the initial index when the framework
  is not up yet.
- **`setZOrderOnTop(true)` puts the GL surface above the window inside its own bounds.**
  Nothing Compose may overlap the view's box: where the character is, the surface wins.
  That is why the call screen puts the listening indicator and the lyrics *below* the
  character area (the lyrics are pinned to exactly two lines — `minLines` as well as
  `maxLines` — because a lyrics area that re-measures with the text resizes the
  character's `weight(1f)` area with it, and the GL surface rescales with its view
  bounds: the character visibly "resized over and over" while it spoke), and why the
  picker's preview Surface contains only the view. The same Z order is why the surface
  does not die with composition: it is not part of the window, pop transitions do not
  animate it away, and `onDispose` only runs when a pop *finishes* — left alone, the
  character kept floating over the incoming screen for a beat after leaving ("ghosting").
  `Live2DView` therefore follows the destination's lifecycle and turns the surface
  `GONE` on `ON_STOP` — Navigation lowers the outgoing entry's lifecycle at the *start*
  of the pop — and restores it on `ON_START`.
- **The Cubism SDK is vendored at the repo root `cubism/`** — `CMakeLists.txt` reaches it
  through a relative `../../../../cubism/...` path from `androidApp/src/main/cpp/`, so
  the layout is load-bearing. NDK/CMake versions are pinned in
  `androidApp/build.gradle.kts` to what the old repo used. Model weights are committed
  APK assets (`androidApp/src/main/assets/<id>/`, three models — the old repo's
  `shizuku` was never selectable, and `rice_pro_en`, `haru_greeter_pro_jp` and
  `mao_pro_en` were dropped from this port on purpose; a device still storing any of
  them degrades to the photo through the -1 fallback, no migration involved — nor is
  the background-image feature or its `hall.png` ported). `CubismBridge.setContext(this)` in `RolesWitchApp.onCreate` feeds
  the JNI
  asset loader — the bridge's `JNI_OnLoad` hard-codes the
  `ai/roleswitch/android/service/CubismBridge` class name, so moving that class breaks
  the library load.
- **`cubismId` is Android-only.** iOS never reads or writes the column; do not add it to
  `EffectiveSettings`/`applyThreadOverrides` (it is not an inference setting, and iOS
  has no engine to render it).

## iOS app (`iosApp/`)

iOS mirrors the Android feature surface: threads/messages, settings, onboarding
(deliberately *not* step-for-step — see the first bullet below), avatars,
per-thread settings overrides, and voice/video calls. It consumes the
`SharedLogic` XCFramework via `AppContainerFactoryKt.createAppContainer()`;
`AppViewModel` (a singleton `ObservableObject`) is the single bridge and binds
the KMP `StateFlow`s to `@Published`s. There is **no Vosk on iOS** and the STT/TTS
are native wrappers, not implementations of the shared `SpeechService` interface.

Platform mappings an agent will otherwise misread from the Android side:

- **Onboarding collects nothing on either platform, and the OpenAI settings start
  empty.** The two steps (`OnboardingView.swift` / `OnboardingScreen.kt`) are
  welcome and privacy — ported from the shipping SwiftUI build `Breakthrough-Swift6`
  (`UI/Onboarding/`), whose own third step (pick a role) is dropped because it never
  persisted its selection there either; Android's former endpoint/token/model step was
  removed to match. `AppViewModel.completeOnboarding()` on iOS and the
  `completeOnboarding` lambda in `OnboardingScreen.kt` both take **no arguments** and
  write `language`, `useVosk` and `hasCompletedOnboarding` — plus `useOnDevice = true`:
  iOS unconditionally (`setUseOnDevice(true)`), Android only when
  `onDeviceInferenceSupported` (API 31+), because below that the engine does not exist
  and the remote endpoint stays the default. `openaiAPI`, `openaiKey` and
  `selectedModel` therefore stay at the `""` they already default to in
  `SettingsManager` until the user fills them in under Settings — do not reintroduce a
  default endpoint or model, and do not add a credentials step back to onboarding.
  `SettingsManager.hasValidModel` (`openaiAPI` and `selectedModel` both non-empty when
  remote) is the gate the chat surfaces use, so a fresh install lands on the threads
  list with sending disabled until configured. Seeding is unaffected:
  `OnboardingView.task` / `OnboardingScreen`'s `LaunchedEffect(Unit)` still call
  `seedDefaults()`.
- **`useVosk` means "offline speech recognition" on iOS**, not the Vosk library.
  The `Settings → Audio → Use offline speech recognition` toggle (and the
  `customUseVosk` per-thread column) drive
  `SFSpeechAudioBufferRecognitionRequest.requiresOnDeviceRecognition`. The field
  is reused as-is for schema parity — do not add a separate offline column.
  It selects a *mode* of one engine — there is exactly one
  `SpeechRecognizerService`, unlike Android's `sttService` vs `voskService` split.
- **iOS has no tap-to-speak: the mic listens continuously in both modes.** Do not
  port Android's `&& !effective.useVosk` guards on `isTimedOut`/`micIsOff` — those
  exist only because `VoskService` never sets `isTimedOut`. On iOS silence, a
  recognizer error, and an empty offline `isFinal` all **recycle the recognition
  task**; `isTimedOut` is raised only after `maxRestartFailures` restarts fail in a
  row, so the mic button keeps a manual escape hatch.
- **Online `SFSpeechRecognizer` never returns `isFinal` on its own** (the result
  is "infinite"). `SpeechRecognizerService` finalizes when the best transcription
  has been stable for 1.5s (Android's `HOLD_TIMEOUT_MS`). Offline mode returns
  `isFinal` natively after silence. A single recognition task is also capped at
  ~60s by the platform, so there is a rolling restart at 50s — taken only between
  utterances so it cannot clip mid-sentence.
- **Every recognizer callback is generation-guarded.** `task.cancel()` reports
  through the same callback as a real failure, with a code that is *not* 203; without
  the guard our own teardown reads as an on-device failure, silently restarts the mic
  mid-turn and permanently downgrades the session to online recognition.
- **`finalMessages` is a `PassthroughSubject`, not a `@Published`.** SwiftUI's
  `onChange` de-duplicates, so a `@Published` final message swallows two identical
  consecutive utterances. (Android gets away with a `StateFlow` only because
  `resumeListening()` resets it to `""` first.)
- **Video calls require only `enableVision` on iOS**, and **barge-in requires only
  `allowInterruptions && headphonesConnected`** (Android additionally gates both on
  `useVosk`, because its video and continuous-listening paths assume Vosk). Do not
  re-add the offline gate on iOS.
- TTS voice selection: chosen name → best-quality (premium, then enhanced) voice for
  the language → default (English → **Samantha**, others → first available). Voice
  names are platform-specific strings, so `EffectiveSettings.voice` does not
  round-trip across iOS/Android. `speak(...)` returns `Bool`: `false` means nothing
  was left after cleaning, so **no `isSpeaking` edge is coming** and the caller must
  resume STT itself. Delegate callbacks are matched against the current utterance —
  a flushed utterance's `didFinish`/`didCancel` can otherwise land after the
  replacement's `didStart` and resume STT into the middle of the reply.
- **iOS has a second speech engine: External (OpenAI-compatible `/v1/audio/speech`).**
  Both settings screens (Settings, per-thread) show a *Speech engine* row whose
  picker offers **System** and **External (OpenAI)** — there is **no Auto option**:
  on iOS "Auto" (neither `useSystemTts` nor `useExternalTts`) resolves to the same
  native voice as System, so a third row would be a duplicate. Recognition and
  synthesis are independent choices: "Process on device" (`useVosk` on iOS,
  `requireOnDeviceVoice` on Android) narrows the *recognizer* and the system engine's
  voice list, but it never gates the engine itself — the External row is offered and
  selected whatever the toggle says, on both platforms (there is deliberately no
  drop-to-Auto in `AppViewModel.setUseVosk`, `SettingsManager.setRequireOnDeviceVoice`,
  or the shared `ttsMode`). External mode hides the standard
  Voice row; its Server row edits the **dedicated external-speech server** —
  URL + token in `externalTtsAPI`/`externalTtsKey` — which is **not** the inference
  `openaiAPI`/`openaiKey`: the two may point at different hosts, editing the speech
  server never touches the inference endpoint/token, and it never clears
  `selectedModel` (an inference model id is meaningless to a speech server). The
  speech server is **per-thread overridable** like the voice and the engine, via
  `customExternalTtsAPI`/`customExternalTtsKey` (DB 25→26) on both platforms; its
  Voice row is a free-form server voice id (empty = server picks) whose Save previews
  the per-language greeting through the server before the sheet dismisses. Playback is
  `ExternalTtsService` (`AVPlayer` of the AAC bytes staged to a UUID-named temp file)
  — deliberately **not** `AVAudioPlayer`: the server sends ADTS AAC, which
  `AVAudioPlayer` rejects through AudioToolbox's strict `AACAudioFile` parser
  ("Open failed / ReadBytes Failed"), while `AVPlayer` decodes via the media pipeline
  that serves streaming AAC. The temp file is deleted on playback end/stop, never at
  the point handing off to the player, which reads the URL asynchronously. Teardown at
  natural end is **deferred ~0.3 s past the completion signal** (both platforms: iOS
  `finishPlayback`, Android's `onCompletion` in `TTSService.playExternal`): the
  signal fires when the renderer reaches the end of the data, which on a device is up
  to a few hundred ms before the output sink (Bluetooth above all) has sounded the
  last buffered samples — releasing the player at the signal itself cut the tail off
  every spoken reply in call mode. The resume edge is raised immediately; only the
  pause/release/file-delete waits out the drain (Android guards the deferred release
  on player identity, iOS on a playback generation). It
  publishes the same `isSpeaking` resume edge as the native synthesizer — the
  call screen branches on `effective.ttsMode == TtsMode.external` and both stop paths
  stop both engines. Errors map to the Android pair:
  `ExternalTtsNotConfiguredException` → "Voice is not configured", anything else →
  "Server error". The per-thread values ride the same
  `customUseExternalTts`/`customExternalTtsVoice`/`customExternalTtsAPI`/
  `customExternalTtsKey` columns as Android, passed through
  `AppContainer.applyThreadOverrides` like every other override.
- **Spoken and displayed text both go through the shared
  `MarkdownProcessor.shared.stripMarkdown`** (`commonMain`), the same one Android's
  `TTSService` and `ConversationLyricsView` use. Do not reintroduce a Swift-side
  stripper — they drift.
- **`CallAudioSession` is the single owner of the `AVAudioSession`.** It handles
  interruptions, route changes and foreground return, and publishes
  `isHeadphonesConnected`. `activate()` is a no-op while already active: the session
  stays active across turns (STT pause must NOT silence the TTS reply) and is
  released only on call end.
- **Every `AVAudioSession` mutation must run inside a `Task.detached`, and the
  `async` overload is NOT the fix.** A main-thread session call trips AVAudioSession's
  own unresponsiveness diagnostic (`AVAudioSession_iOS.mm` "can lead to UI
  unresponsiveness if called on the main thread") and races the media pipeline into
  `FigApplicationStateMonitor err=-19431` (seen after the AVPlayer external-TTS
  switch). This was originally attributed to the *synchronous* `setActive(_:options:)`
  and "fixed" by switching to `await setActive(_:options:)` — **that fix does not
  work.** The `async` overload still performs the blocking work on the calling actor,
  so from the main actor it logs the identical diagnostic. Verified by repro: making a
  call, ending a call, and opening the voice sheet all still warned with the async form
  in place. What silences it is getting the call **off the main thread**, so
  `setActive`/`setCategory`/`setMode` each run in
  `Task.detached(priority: .userInitiated) { … }.value` — confirmed on device: the
  warning is gone from all three paths. The functions stay `async` and keep their
  signatures — only their bodies moved; SwiftUI call sites still wrap them in
  `Task { await … }`. Applies to: `activate()`, `deactivate()`, `setVoiceChatMode()`, and
  the voice / external-voice sheets' `beginPreviewAudio()`/
  `endPreviewAudio()`. `VoicePickerSheet.beginPreviewAudio()` is the reference
  implementation. Note `setMode` is **not** exempt after all —
  the earlier claim that only activate/deactivate are flagged was wrong, and is why
  barge-in mode switches went through the same treatment.
- **The `AVAudioEngine` stays running for the whole call, not per turn** — this is
  what makes talking with the screen locked work, and it is load-bearing. `Info.plist`
  declares `UIBackgroundModes: audio`, but iOS honours that only while the app is
  *actually* running audio I/O. Stopping the engine between turns (during the LLM
  request, or while TTS speaks) leaves a window in which locking the screen suspends
  the app; the recognizer then cannot restart, `isTimedOut` latches, and the call
  renders as "You're muted". So `stopRecognizer()` ends the recognition task only;
  `teardownAudio()` (engine + tap) runs from `stopRecording`, an interruption, or a
  route change. Between turns the tap has nowhere to deliver (`AudioSink` holds no
  request), so nothing is transcribed and the mic never gaps. Android gets the
  equivalent from `LlmForegroundService`.
- **`CameraController` sets `automaticallyConfiguresApplicationAudioSession = false`.**
  Left at its `true` default, starting the capture session reconfigures the app's
  audio session and breaks the turn loop the moment video starts. `stop()` also
  detaches the device input, not just `stopRunning()`, or the camera-in-use indicator
  stays lit. (Android's `CameraManager.shutdown` does release — `onBound(provider, camera)`
  records the provider off the `bindToLifecycle` return and `unbind()`/`shutdown()` call
  `unbindAll()` on it, so the iOS `stop()` counterpart must actually detach. Do not port a
  no-op.)
- **The video overlay is a `ZStack` layer, not a `fullScreenCover`.** Android draws it
  inside the same composition; a `fullScreenCover` hosts it in a separate controller
  where the reply text stops tracking. Both surfaces render the same
  `ConversationLyrics` view, whose branch order is Android's `ConversationLyricsView`
  verbatim. The reply is shown **whole, at once** — neither platform streams or
  reveals it progressively.
- **Android parity is about wording and behaviour, never about sheet chrome.** When a
  screen is aligned with Android's, what carries over is the copy (same title/labels,
  same `ru`), the value formats (e.g. the masked `••••••••` token, `Optional`), the
  field placeholders (`hf_...`), the control *kind* (a toggle stays a toggle, a picker
  becomes a toggle where Android uses one) and the row order. The sheet itself stays in
  the house shape every other sheet uses — `NavigationStack` + `Form` with a toolbar
  (`Button(role: .close)` cancellation, `Button(role: .confirm)` confirmation) and, for a
  compact sheet, `.presentationDetents([.medium, .large])` applied at the
  **presentation site**, where every sibling sets it — only the big editing surfaces
  (`ServerSheet`, `LiteRtModelView`) present full-height. (`HuggingFaceTokenSheet`
  initially shipped with neither the toolbar shape nor the detents; both were fixed.)
  Do **not** port Android's Material layout into the content: no hand-rolled
  Reset/Cancel/Save button rows, no mimicked `ModalBottomSheet`, no stroked-field
  imitations of `OutlinedTextField`.

### On-device inference on iOS (LiteRT)

`iosApp/iosApp/LiteRT/` — the native `LiteRTLM` rider on **the same Kotlin catalogue**:
`LitertModels.registry` (commonMain) ships each model with its pinned commit and download
URL, and
`LiteRtModelsInterop` converts `LitertModels.shared.all` to the Swift `LiteRtModel` value
type — there is no second copy of the model list to keep in step with Kotlin's, and
`EffectiveSettings` reads the same registry that ships the picker. `LiteRtService` (a
`@MainActor` `ObservableObject`) holds the one resident engine (`initState` →
overlay/banner, `visionActive`, `downloadState`); `LiteRtInferenceEngine` is the
`InferenceEngine` conformance; `LiteRtDownloader` is the background-`URLSession` fetch;
`LiteRtChat` builds the runtime `Message`s; `LiteRtWarmUp` carries the three SwiftUI pieces
that mirror `LitertWarmUp.kt`; `LiteRtModelView` is the model picker/installer.

- **The package is `litert-lm` SPM from `SwiftPackageManager.LiteRTLM` (`exactVersion 0.18.0`),
  product `LiteRTLM` (the `AIEdgeLiteRTLMSwift` bindings).** The package ships prebuilt
  binaries, so no `-skipPackagePluginValidation` is needed. Android runs the *same
  runtime* through its own artifact `com.google.ai.edge.litertlm:litertlm-android` (version
  `0.18.0` in `libs.versions.toml`) — **both platforms are pinned to the same runtime version**;
  a runtime behavior change lands on both at once and should be checked on both platforms
  together.
  `iosApp.entitlements` still carries
  `com.apple.developer.kernel.increased-memory-limit`, without which a 2–4 GB model is jetsammed.
- **`EffectiveSettings.isConfigured` for the on-device engine is `litertModelSpec != null`** —
  there is no second id source on iOS any more. **Both chat and call must gate on
  `isConfigured`**, never on `openaiAPI`/`selectedModel` — those are empty on a perfectly working
  on-device thread, and testing them is a bug. The `litert*` driver fields
  (`litertAccelerator`, sampler strings…) default to `""`, which the engine reads as "the
  checkpoint's default"; do not force non-empty storage for them.
- **`LiteRtService` is `@MainActor`; the runtime types it touches are actors too.** Kotlin calls
  `LiteRtInferenceEngine.generate` from a coroutine on an arbitrary thread, so the conformance is
  not main-actor isolated and hops onto the service's actor itself. `Engine.initialize()` is a
  blocking call lasting tens of seconds — it must be awaited from a non-UI executor (the engine
  is an actor, so the call hops off the main actor), which is what the warm-up overlay covers.
  **A `Conversation` is rebuilt every turn** from the trimmed history (LiteRT's conversation is
  stateful and the rest of the app is not); **the multi-gigabyte `Engine` is *not*** — it is
  keyed by file path/accelerator/maxTokens/vision (`EngineKey`) and only replaced when one of
  those changes. ARC is the closing: a conversation's deinit deletes its native handle, so the
  native teardown Android serialises with locks is automatic here.
- **Every unload that matters is a no-op while `isGenerating`.** "Dropping the weights out from
  under a running generation" reads as a cancelled turn in the old engine and a crash in both of
  the native ones; `release()` guards on the flag, and the per-turn stream holds its own
  references anyway.
- **Backgrounding cancels the turn and unloads the model** (`AppViewModel.isAppForegrounded`'s
  `didSet` → `stopOnDeviceWork()` → `LiteRtService.stopForBackground()`). This is the opposite of
  Android, where backgrounding deliberately keeps the engine resident, and it is not optional: iOS
  terminates a suspended app with GPU work outstanding. The remote engine is untouched — it is
  designed to keep streaming in the background, which is what the `UIBackgroundTask` around
  `isStreaming` is for — so the teardown is gated on `litert.isGenerating`, and the cancel goes
  through `LLMService.cancelRequest()` rather than only the engine, so the shared side clears
  `isStreaming` and offers `pendingCancellationRetryThreadId`. `AssistantView` ends an on-device
  call on the same transition.
- **`stopForBackground()` awaits the generation task before dropping the container**
  (`task?.cancel(); _ = await task?.result`), and it must **not** cancel a download — a network
  transfer runs in `nsurlsessiond` outside this process, survives suspension anyway, and
  cancelling it there was what threw a model install away every time the screen locked.
- **The model unloads on exactly three things: a changed model, a changed engine, and
  suspension.** Not on leaving a screen. `releaseIfNotFor` compares the wanted `EngineKey`
  against the loaded one and returns early when they match, so moving between the chat and the
  call — where the chat sits alive underneath the call's `fullScreenCover` and runs its own sync —
  costs nothing. An earlier engine version released on screen departure and dropped the model
  mid-call; the LiteRT lifecycle is the fix for that history.
- **Never hand the engine sync placeholder settings.** Both chat and call resolve
  `EffectiveSettings` against `AppContainer.threadForId` until the real row arrives from Room, and
  that placeholder has `useCustomSettings = false` — so a thread that overrides the model looks,
  for one frame, like a thread wanting the *global* one. Both screens therefore pass a
  `resolvedSettings` that is nil until `currentThread` is set; `effective` (placeholder-backed) is
  still fine for the send gate and the vision check, which re-read on the next redraw.
- **A call holds the model resident** (`retain`/`relinquish`, taken by the eager sync — the call
  screen passes `eager: true` and the chat `false`). The only thing that consults the hold is the
  memory-warning path (`releaseIfUnused`): a warning arriving *between* two turns of a call found
  `isGenerating == false` and unloaded the model the user was still talking to.
- **Weights live at `appDataDirectory()/litert/<id>/<commitHash>/`, one file per model, and
  `isDownloaded` reads the filesystem — size only.** Not `URL.cachesDirectory` + a
  `UserDefaults` flag as in the source app: iOS purges that directory while the flag survives,
  and the app then tries to load a model that is gone. Size equality (`fileSize == sizeBytes`) is
  the only check possible for gated models (their SHA-256 sits behind the same auth wall as the
  weights) and it is the check that stops a truncated download from looking installed. Literacy
  note: LiteRT writes its compiled-weights cache **next to the model file** (`cacheDir: nil`), so
  the whole `<id>/<commitHash>/` directory is the unit — `deleteModel` does
  `removeItem(at: model.directory)`.
- **The app downloads one file per model itself, on a background `URLSession`**
  (`LiteRtDownloader`). The two reasons are really one: the transfer has to belong
  to us. An in-process `URLSession` dies with the app; a background session's transfers run in
  `nsurlsessiond` and are the only mechanism iOS offers for a download that outlives suspension.
  And a bare `downloadTask` without a delegate reports nothing until it completes; with one,
  `didWriteData` is per byte. Keep `isDiscretionary = false`, `sessionSendsLaunchEvents = true`,
  and a long `timeoutIntervalForResource` (the default 60 s would abandon a 4 GB fetch).
- **`stopForBackground()` must not cancel a download.** It cancels the *turn* and unloads the
  weights, because iOS terminates a suspended app with GPU work outstanding. A network transfer
  is not GPU work and no longer belongs to this process at all.
- **Resume is a `Range` request, not a re-fetch.** A truncated file is a corrupt model, so a
  retry sends `bytes=<partial>-` and appends the 206 body to what arrived; a server that ignores
  the range answers 200 with the whole body, which the finishing path detects (status 206 vs not)
  and overwrites. **Gated repos** need `Authorization: Bearer <token>`, and the downloader throws
  a `needsToken` error up front when the app has no token to send — a gated model fetched
  anonymously is a 401 that would otherwise only surface deep inside the engine.
- **A download task's destination lives in its `taskDescription`** (`<modelId>\n<path>`). It is
  the only per-task state iOS preserves across a relaunch, and a background transfer can outlive
  the process: the app is woken through `application(_:handleEventsForBackgroundURLSession:)`
  with no `Job` in memory, `didFinishDownloadingTo` files the bytes away from that string alone,
  and `handleBackgroundEvents` calls the handed-over completion on the main queue.
  `AppDelegate.adoptOutstandingTasks()` re-attaches at launch for the same reason.
- **`LiteRtModelView` is the model picker/installer.** Staged selection flow intact from the
  source cards: tapping a card only *stages* it, a not-installed model asks first ("Download
  required", which is where a gated model's token need surfaces), and **Save** is what writes the
  id — nothing is persisted for a model whose weights are not on disk. Before a download it
  checks free space against 1.5× size (matching Android's `missingSpaceBytes`). It is a `List`
  with hidden separators and a clear row background so the cards can carry `swipeActions` for
  delete — and download progress lives *in* the card being installed. The `"No models available
  for your device"` empty state is now unreachable: **`availableModels()` applies no RAM filter**
  (see below).
- **Do not put a row-wide `onTapGesture` on a row that contains buttons.** It wins hit-testing
  against them (this killed the Download button once). Anything tappable in a `Form`/`List` row
  also needs `.buttonStyle(.borderless)` or `.plain` to be its own target.
- **There is no RAM filter — do not reintroduce one.** An earlier `availableModels()` hid models
  whose `minDeviceMemoryGb` exceeded the device, and it hid *everything* on common iPhones.
  Correcting the tiers during the engine swap (E2B 8→6 GB, E4B 12→8 GB) was not enough:
  Google's tier is advisory and conservative, and Apple's `ProcessInfo.processInfo.physicalMemory`
  reports less than the marketed RAM (an 8 GB iPhone reports ~7.67 GB, so even the 6 GB E2B tier
  with `≤` failed to clear it). Android merely warns and never blocks; iOS now matches. The
  model's own download size is the visible cost.
- **Vision capability is load-time, not registry.** The engine keeps `visionAvailable` (`supportsImage`
  for a LiteRT checkpoint) separate from `LiteRtService.visionActive` — *what actually brought up*,
  because a vision model whose required GPU backend is unavailable is loaded text-only rather than
  not at all. `visionAvailable` gates whether images may be attached and hides the `Enable vision`
  toggle; `visionActive` decides whether the sent turn includes images. `LiteRtChat.turnContents`
  passes the attachment bytes straight through — the same JPEG bytes the OpenAI-compatible path
  sends to the server, **no PNG round-trip, no crop/resize** — and places images *first*, because
  the runtime anchors generation on the final token. **No `visualTokenBudget` is set on the
  conversation**: nil lets the checkpoint's own config decide the per-image patch budget, exactly
  as Android and the gallery do. (This used to pass `maxImagesPerTurn` (10) as the budget — the
  runtime reads that as a per-image *token* budget, valid choices 70–1120, so every image was
  capped at 10 × 9 = 90 patches and on-device vision could not read text.) The vision backend is
  always the model's own `visionAccelerator` (GPU for the Gemma family), never the user's
  accelerator choice, exactly as on Android.
- **MCP tools are not wired to iOS on-device inference.** `LiteRtInferenceEngine` accepts
  `toolSpecs`/`callTool` and ignores them, and the conversation is built with `tools: []` — with
  no tools declared the model cannot emit a tool call the runtime would choke on, which is why
  streaming needs no tool loop. This is why **both settings screens hide the Tools section for
  the on-device engine** (`SettingsView` on `app.useOnDevice`, `ThreadSettingsView` on the
  thread's *effective* engine, so a thread inheriting the global choice is covered). An
  already-stored selection is left alone rather than cleared, like `customEnableVision`. Note
  this puts the only route to `ToolsView` behind the global engine being cloud.
- **App Store validation demands a dSYM for every UUID-bearing embedded binary, and Google
  ships none.** `CLiteRTLM.framework` (dynamic, embedded) carries an `LC_UUID`, but the
  `litert-lm` artifact bundle contains no dSYM for it — the missing-dSYM upload error names only
  it because `SharedLogic.framework` (Kotlin/Native) has no UUID at all. The `Generate dSYM
  Stubs for Prebuilt Frameworks` script phase fixes it at archive time: for every `*.framework`
  in `$BUILT_PRODUCTS_DIR` that has a UUID and no sibling dSYM, `dsymutil` fabricates a
  UUID-only dSYM (it stamps the binary's UUID even with zero debug info to carry) into the build
  products, and Xcode's archive action copies it into `<archive>/dSYMs`. The phase runs only on
  install actions (`$ACTION = install`), so normal builds skip it. It contains no real symbols —
  CLiteRTLM frames were never symbolicable — and it is not a reason to bump the litert-lm pin.
- **Control tokens and thinking.** The stream interleaves the model's internal control tokens
  (`<ctrl` prefix) with user-facing text; the reference gallery filters the former. With
  thinking enabled (`supportsThinking && litertEnableThinking`), reasoning arrives on the
  `"thought"` channel and is accumulated into `GenerationResult.reasoning`, mirrored by the
  streaming flows' `reasoning` side. The user's accelerator offer on iOS is CPU-first with GPU
  as an opt-in (Advanced), matching Android's default; GPU requests get a CPU fallback attempt
  after the GPU one fails to build — the text backend bounces `["gpu","cpu"]`, never the vision
  backend.
- **Sampler settings are read per turn from `EffectiveSettings`, not from a per-model blob.** A
  thread's own tuning applies without the engine holding settings from a different thread; blank
  strings mean "the checkpoint's default" and the runtime's `SamplerConfig` throwing-guard is
  satisfied by clamping user values into range before it is constructed.

Swift/Kotlin interop facades (in `commonMain`) exist because Kotlin data-class
`copy(...)` and default args don't export to ObjC/Swift: `AppContainer.threadForId`
(placeholder `MessageThread` for `EffectiveSettings.resolve` before the row
loads), `AppContainer.applyThreadOverrides(thread, 33 native params)` (iOS can't
build a 19-arg `EffectiveSettings`), and `AppDatabase.applyThreadOverrides` /
`disableThreadOverrides` / `getAllThreads`. `SettingsInterop` bridges the
`SettingsManager.companion` language/locale maps (Kotlin `Map` → `NSDictionary`).
Info.plist carries `NSMicrophoneUsageDescription`, `NSSpeechRecognitionUsageDescription`,
`NSCameraUsageDescription`, `NSPhotoLibraryUsageDescription`.

## Entrypoints

- Android wiring: `RolesWitchApp` (`androidApp/.../RolesWitchApp.kt`) is the
  `Application`. It constructs the singletons (DB, settings, services) in
  `onCreate`. `MainActivity` reads `app.settingsManager.hasCompletedOnboarding`
  to pick `OnboardingScreen` vs `MainApp`.
- KMP DI surface: `AppContainer` (`sharedLogic/.../app/AppContainer.kt`) is the
  intended cross-platform container. On Android it is currently not used (the
  `Application` holds the singletons directly); iOS builds one via
  `createAppContainer()` in `sharedLogic/src/iosMain/.../AppContainerFactory.kt`.
  If you add shared functionality, hang it off `AppContainer` rather than passing
  singletons manually.
- Platform factories: `getAppDatabase(context)` and `getSettingsManager(context)`
  live in `sharedLogic/src/androidMain` and are double-checked-locking singletons.

## Build / verify commands

> **Prefix every Gradle/xcodebuild command with the `JAVA_HOME` export from the
> [Environment](#environment-set-up-before-running-any-gradlexcodebuild-command)
> section**, or it will fail with `Unable to locate a Java Runtime`.
> **Prefix every `xcodebuild` command with the `DEVELOPER_DIR` export from the
> same section**, or it will fail with `requires Xcode`.

- Clean build of everything Gradle manages: `./gradlew build`
- Android debug APK: `./gradlew :androidApp:assembleDebug`
- Shared tests (all targets): `./gradlew :sharedLogic:allTests`
- Single Gradle task/module is fine — e.g.
  `./gradlew :androidApp:compileDebugKotlin` (compiles `:sharedLogic`'s Android target
  too; the `:sharedLogic:compileKotlinAndroid` name this file used to cite does not
  exist under the AGP 9 KMP plugin).
- iOS commands and the **arm64-simulator-only** requirement (no `iosX64()` is
  declared; `generic/platform=iOS Simulator` fails) are documented in
  `README.md` — follow them exactly. For the simulator build, prefer the
  name-based destination `-destination 'platform=iOS Simulator,name=iPhone 17'`
  over looking up the UDID via `xcrun simctl list devices` — it is shorter,
  deterministic, and resolves to the arm64 simulator automatically.

There is no separate `lint`/`typecheck`/`format` task configured; correctness is
checked via `compileKotlin*` and tests. Always recompile after KMP/Room changes.

### Do not run the app to verify — unless explicitly asked

**Compiling is the verification.** Do not boot an emulator or simulator, install
an APK, drive the UI with `adb shell input`, or take screenshots to confirm a
change works. Report what the build tells you and stop there. The maintainer runs
the app themselves and will ask when they want a run — only then is it in scope.

This applies to visual/behavioural confirmation of your own work. It does not
block **diagnosing a failure the maintainer reports**: reading `adb logcat`
(including `-b crash`) on an already-installed build is a read-only diagnostic and
is fine.

**Never install onto a physically attached device.** `adb devices` here usually
lists the maintainer's own phone alongside any emulator; it holds real data, and
`fallbackToDestructiveMigration(true)` means a bad DB version wipes it. If a run
is ever requested, target an emulator explicitly with `-s emulator-NNNN`.

## Tests

- **There are currently no tests.** `commonTest` is declared as a source set in
  `sharedLogic/build.gradle.kts` but the `sharedLogic/src/commonTest/`
  directory does not exist, and `:androidApp` has no `*Test` sources either.
  Do not assume a green suite means tests pass — it usually means none ran.
- Per the README: AGP 9.x's `com.android.kotlin.multiplatform.library` plugin
  only picks up Android host tests under `androidHostTest` when `withHostTest { }`
  is enabled on the android target. Until that block is added, put shared tests
  in `commonTest`, not `androidHostTest`/`androidUnitTest`.
- To run a single test class once tests exist:
  `./gradlew :sharedLogic:jvmTest --tests "fully.qualified.ClassName"` (a JVM
  target is not currently declared, so prefer `:sharedLogic:allTests` or the
  iOS/Android-specific tasks).

## Room / data

- DB version is `28` (`AppDatabase.kt`), `exportSchema = false`. In order:
  `6_7` adds the `avatarBytes` BLOB; `7_8` adds the per-thread `custom*` override
  columns; `8_9` adds `customUseSystemTts`; `9_10` creates `mcp_tools`; `10_11`
  adds `customUseTools` + `customToolIds`; `11_12` clears both on threads with
  `useCustomSettings = 0`; `12_13` rebuilds `message_threads` without
  `customUseTools`; `13_14` rebuilds `mcp_tools` without `isEnabled`; `14_15`
  adds `customReasoningEffort`; `15_16` rebuilds `roles` with `greeting` in place
  of `hasGreeting`; `16_17` adds the seven `customUseLiteRt` / `customLitert*`
  on-device overrides (engine, model, sampler, accelerator); `17_18` adds
  `customLitertEnableThinking`; `18_19` adds six legacy per-thread override columns
  that a former engine once chose with (removed again by the `21_22` rebuild); `19_20`
  drops `isProtected` from
  `message_threads` and `roles` — nothing is protected any more, so both the default thread
  and its role are editable like any other; `20_21` deletes every `roles` row but
  `roles_witch` — the role-template picker is gone, so the table's only remaining consumer
  is the seed's empty-check, and a fresh install seeds just `roles_witch`; `21_22`
  rebuilds `message_threads` dropping the six legacy override columns added by `18_19`
  (CREATE/COPY/DROP/RENAME — `ALTER TABLE … DROP COLUMN` needs SQLite 3.35+, above this
  `minSdk`); `22_23` adds the per-thread `customStreamingMode` override; `23_24`
  adds the `documents` JSON column to `messages`; `24_25` adds the two per-thread
  external-speech overrides `customUseExternalTts` + `customExternalTtsVoice` (the third
  speech engine, OpenAI `audio/speech`); `25_26` adds the per-thread external-speech
  *server* overrides `customExternalTtsAPI` + `customExternalTtsKey` — the speech
  endpoint and token, separate from the inference `customApiUrl`/`customApiKey`, so a
  thread may speak through a different host than it chats; `26_27` rebuilds
  `message_threads` dropping `customVoiceSpeed` — the per-thread "Voice Speed"
  override, whose feature is gone outright (neither engine takes a rate any more);
  `27_28` adds the per-thread `cubismId` column — the bundled Live2D character a
  thread presents itself through on a call (Android-only feature; null = the
  avatar photo, so every existing row keeps its photo). Every one must be
  registered on the Android **and** iOS
  factories — missing either falls back to a destructive migration on that
  platform only. The KSP arg
  `room.schemaLocation = ${projectDir}/schemas` is set but the
  `sharedLogic/schemas/` directory is never populated because export is off —
  do not commit generated schema JSON expecting it to appear.
- **One version number per schema shape — never edit a version someone has already
  run.** Room only migrates when the number changes; reopening at the same version
  with a different entity shape skips migrations and dies on the identity-hash check
  (`Room cannot verify the data integrity … Expected identity hash: … found: …`).
  A version that never reached the store has still usually reached a dev device, so
  add the next version rather than amending the last one. Column drops need a full
  create/copy/drop/rename rebuild: `ALTER TABLE … DROP COLUMN` needs SQLite 3.35+,
  above this `minSdk`.
- `AppDatabaseFactory` uses `fallbackToDestructiveMigration(true)`. **Bumping
  `@Database(version = ...)` wipes all user data on next launch.** Write a
  proper `Migration` if user data must survive.
- The default welcome thread has a fixed ID
  `RoleSeedData.DEFAULT_THREAD_ID = "00000000-0000-0000-0000-000000000000"`.
  Seeding is idempotent (`AppContainer.seedDefaultThread` /
  `seedRolesIfEmpty`) and is the only thing keeping the app usable on first run.
- **Onboarding is the only place that seeds, on both platforms.**
  `OnboardingView.task` / `OnboardingScreen`'s `LaunchedEffect(Unit)` — nothing else.
  Settings → Reset wipes and stops: `SettingsManager.reset()` sets
  `hasCompletedOnboarding` to false on a `StateFlow` both platforms observe, so the
  reset lands on onboarding and onboarding does the seeding. Do not "restore" a
  re-seed to the reset path — that is what it used to do, and the two seeds
  overlapped: both read an empty table, both inserted the fixed
  `DEFAULT_THREAD_ID`, and the loser threw
  `UNIQUE constraint failed: message_threads.threadId` — fatal on iOS, where
  `SQLiteException` is in no `@Throws` list and so terminates the process instead of
  arriving as an `NSError`.
- **The seed functions hold `AppDatabase.seedMutex`, and must keep holding it.** Both
  are check-then-insert across a suspension point, so they are idempotent only if they
  cannot interleave — and "one caller" is an invariant that has already been broken
  once. The mutex is not re-entrant, hence the private `seedRolesIfEmptyLocked` that
  `seedDefaultThread` calls from inside its own hold.
- `MessageThread.threadId` defaults to a random UUID; do not pass `null` or
  hand-construct IDs without going through the DAOs.

## KSP / buildkonfig quirks

- `-Xexpect-actual-classes` is set at the top of the `kotlin { }` block in
  `sharedLogic/build.gradle.kts` to silence Room's generated `actual object`
  companions (KT-61573). Do not remove it; remove only if Kotlin promotes
  expect/actual classes to stable.
- The `buildkonfig` plugin's `defaultConfigs {}` is currently empty and
  `sharedLogic/.../BuildConfig.kt` is a hand-written empty `object BuildConfig`.
  To add a build flag, configure it in the `buildkonfig { }` block **and**
  delete the hand-written `BuildConfig.kt` to avoid a duplicate-declaration
  error.

## iOS target — must keep compiling

iOS is now an active, feature-complete app (see [iOS app](#ios-app-iosapp) above)
but **must continue to build** (see README "# Architecture" and the iOS notes in
"# Build"). Do not remove:

- the `SharedLogic` XCFramework target / `iosArm64()` + `iosSimulatorArm64()`
  declarations in `sharedLogic/build.gradle.kts`,
- the `embedAndSignAppleFrameworkForXcode` run-script phase in
  `iosApp/iosApp.xcodeproj`.

The `iosMain` source set contains the platform factories (`AppDatabaseFactory`,
`SettingsManagerFactory`, `AppContainerFactory`) that wire the iOS app.

## Conventions

- `kotlin.code.style=official` (set in `gradle.properties`).
- Logging uses `co.touchlab.kermit` (`Logger.withTag(TAG)`) throughout shared
  code; Android UI code uses `android.util.Log`. Prefer Kermit in `commonMain`.
- Dependency versions are centralized in `gradle/libs.versions.toml`. Add new
  libraries there and reference via `libs.*` accessors — do not inline versions
  in `build.gradle.kts`.
- **Both platforms are localized (en + ru).** Android lives in
  `androidApp/src/main/res/values*/strings.xml`; iOS in
  `iosApp/Localizable.xcstrings`, with `ru` present in the project's
  `knownRegions`. The two `ru` sets are kept in step: when a string exists on
  both platforms the iOS `ru` value mirrors Android's `values-ru` entry, so
  add or reword it in the same change. A freshly synced iOS key lands in the
  catalogue as an empty entry — fill in its `ru` block before calling the
  string done.
- The seed roles are content, not UI chrome, and are unaffected — they still ship
  `en` + `ru` translations in `RoleSeedData.kt` (shared, so both platforms get
  them). When adding a seed role, provide at least an `en` translation (fallback)
  and a `ru` one. There is just the one role now — `roles_witch` — because the
  role-template picker is gone: the new-chat sheet on both platforms pre-fills
  from `NewThreadDefaults` (also `en` + `ru`), and the `roles` table is read only
  by the seed's empty-check. If the template picker ever comes back, both
  surfaces are in `RoleSeedData` and `ThreadsScreen`/`ThreadsView`.
- **iOS localization goes through plain literals, not an accessor.** There is no
  `Strings.swift`/`L10n` any more: `Text("Create")`, `Button("Save")`,
  `Section("Audio")` and friends take `LocalizedStringKey`, so the English source
  text *is* the catalogue key. Outside those positions use
  `String(localized: "…")`. Two rules keep it from breaking:
  - **Never localize through a ternary or a `+`.** `Text(cond ? "A" : "B")` picks
    between the `LocalizedStringKey` and `StringProtocol` initializers
    unpredictably, and a concatenation is not a key at all. Branch with `if`/`else`
    around two literal `Text`s, or resolve each side with `String(localized:)`
    first. Helper views whose parameter is always chrome (`AdvancedField.label`,
    `SettingsView.row(label:)`) take `LocalizedStringKey` for the same reason.
  - **User and model content renders with `Text(verbatim:)`.** Message bubbles,
    thread titles and previews, live transcripts, the reasoning trace, model and
    voice names, API errors, and the example endpoints used as field prompts are
    all `verbatim`. `Text(someString)` happens not to localize today, but the
    explicit form is what stops a later refactor to a literal from translating
    someone's conversation.

  `SWIFT_EMIT_LOC_STRINGS = YES` is on, so the compiler emits `.stringsdata` for
  every file. After changing strings, re-sync and check nothing drifted:
  ```sh
  D=$(dirname "$(find ~/Library/Developer/Xcode/DerivedData/iosApp-*/Build/Intermediates.noindex/iosApp.build/Debug-iphonesimulator/iosApp.build/Objects-normal -name 'MessagesView.stringsdata' | head -1)")
  xcrun xcstringstool sync iosApp/Localizable.xcstrings --stringsdata "$D"/*.stringsdata
  git diff iosApp/Localizable.xcstrings
  ```
  A key the sync adds is one you missed; a key it marks `"extractionState": "stale"`
  no longer exists in source. Plain `xcodebuild` does **not** write back to the
  catalogue — only Xcode.app and this command do.
