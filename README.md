# Roles Witch OSS
An open-source, privacy-first AI assistant for Android and iOS — voice & video calls, on-device inference, no subscriptions.

# About

**Roles Witch** is a privacy-focused AI assistant for Android and iOS, built
with Kotlin Multiplatform. Create your own characters ("roles"), then talk to
them naturally — by text, by voice, or on a video call where you can show
objects and scenes live.

- 🎙️ **Voice & video calls** — speak with your assistant instead of typing;
  interactive visual conversations are processed right on your phone.
- 🔒 **Private by design** — with the on-device engine (Google LiteRT-LM),
  inference runs entirely offline: your voice, prompts, and chat history
  never leave the phone. The only network activity is a one-time HTTPS
  download when you install a new model.
- ☁️ **Bring your own cloud** — alternatively connect any OpenAI-compatible
  endpoint; the engine is selectable per chat under
  Settings → Intelligence → Engine.
- 🧩 **Custom roles** — characters with distinct personalities, backstories,
  and traits; switch between them per thread.
- 💾 **Local-first storage** — chat history, threads, and roles live on the
  device (Room); settings in DataStore. Markdown rendering, camera
  attachments, and offline speech recognition (Vosk) + TTS round it out.
- 🆓 **No subscription, no tiers** — every feature is in the one app, and the
  source is right here.

The app is published in the app stores. This repository is open source so you
can see exactly what runs on your device and build it from source. Learn more
at [roleswitch.ai](https://roleswitch.ai/).

## Project layout

- [`androidApp/`](./androidApp) — the Android app (Jetpack Compose).
- [`sharedLogic/`](./sharedLogic) — the Kotlin Multiplatform core shared by
  both platforms: data (Room), services (LLM, speech, OpenAI client), settings.
- [`iosApp/`](./iosApp) — the iOS app (SwiftUI), consuming the shared
  `SharedLogic` framework built from `:sharedLogic`.

# Build

## Prerequisites

- **Android**: JDK 21 (no standalone JDK? Android Studio's bundled runtime works:
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`) and the
  Android SDK (installed via Android Studio, or set `sdk.dir` in `local.properties`).
- **iOS**: Xcode with an iOS SDK. The Kotlin `SharedLogic` framework is compiled automatically by the
  Xcode build phase (`embedAndSignAppleFrameworkForXcode`), which also sets `JAVA_HOME` itself.

## Android

```shell
# Debug APK
./gradlew :androidApp:assembleDebug
# → androidApp/build/outputs/apk/debug/androidApp-debug.apk (signed with the debug key)

# Release APK
./gradlew :androidApp:assembleRelease
# → androidApp/build/outputs/apk/release/androidApp-release.apk

# Release AAB (Play Console upload)
./gradlew :androidApp:bundleRelease
# → androidApp/build/outputs/bundle/release/androidApp-release.aab
```

Release artifacts are signed with the keystore configured in `~/.gradle/gradle.properties`
(`roleswitchStoreFile` / `roleswitchStorePassword` / `roleswitchKeyAlias` / `roleswitchKeyPassword`).
Without these properties the release build is left unsigned.

## iOS

Build from the command line:

```shell
# Debug, iOS Simulator
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  ARCHS=arm64 -derivedDataPath iosApp/build build
# → iosApp/build/Build/Products/Debug-iphonesimulator/RolesWitch.app

# Release, device (signed with the Apple Development identity of DEVELOPMENT_TEAM)
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Release -destination 'generic/platform=iOS' \
  ARCHS=arm64 -derivedDataPath iosApp/build build
# → iosApp/build/Build/Products/Release-iphoneos/RolesWitch.app
```

> **Building for your own device** requires one-time setup: install Xcode, create an
> Apple Developer account (the free tier is enough for running on your own devices),
> then open `Signing & Capabilities` in Xcode and select your account's team
> (Xcode → Settings → Accounts to sign in first). Xcode will then manage signing
> automatically for personal-device installs.

`ARCHS=arm64` is required because the Kotlin targets are `iosArm64` + `iosSimulatorArm64` only —
the generic simulator destination would otherwise also request x86_64 and fail validation.
Alternatively open `iosApp/iosApp.xcodeproj` in Xcode and press Cmd+R.

### Xcode cannot resolve LiteRTLM ("git-lfs not found")

If Xcode fails to resolve the LiteRT-LM package with a `git-lfs` error while command-line
builds work: the LiteRT-LM checkout is stored in git-lfs, and Xcode's git cannot find the
`git-lfs` binary because it was installed with Homebrew (`/opt/homebrew/bin`) and Xcode
does not inherit your shell's `PATH`. Verify with:

```shell
which git-lfs          # → /opt/homebrew/bin/git-lfs (empty if git-lfs is missing)
git --exec-path        # → where Xcode's git looks for its own tools
ls "$(git --exec-path)/git-lfs"   # → "No such file or directory" confirms the problem
```

Fix by symlinking `git-lfs` into git's exec path:

```shell
sudo ln -s "$(which git-lfs)" "$(git --exec-path)/git-lfs"
```

Then fully quit Xcode (Cmd+Q), and use **File → Packages → Reset Package Caches** before
reopening the project. If the old error keeps showing, delete Xcode's stale package cache —
note that CLI builds with `-derivedDataPath iosApp/build` use a separate copy, so also remove
DerivedData folders that predate the fix:

```shell
rm -rf ~/Library/Developer/Xcode/DerivedData/iosApp-*
```

> First iOS build only: the app resolves the native Google **LiteRT-LM** Swift package
> (pinned to the same runtime version the Android app uses). The checkout is large —
> several GB of git-lfs objects for all platforms — but it is a one-time fetch, cached
> under `~/Library/Caches/org.swift.swiftpm`.

## Tests

```shell
./gradlew :sharedLogic:allTests
```

# Architecture

Kotlin Multiplatform with a thin UI shell on each platform:

```
androidApp (Jetpack Compose) ─┐
                              ├─► sharedLogic (Kotlin Multiplatform core)
iosApp (SwiftUI) ─────────────┘        │
                                       ├─ commonMain — shared logic
                                       ├─ androidMain — Android actuals
                                       └─ iosMain — iOS actuals
```

- **`androidApp/`** — the Android shell: Jetpack Compose UI plus Android-only
  services (Vosk offline STT, Android TTS, CameraX camera attachments) and the
  PDF extraction actual. Depends on `:sharedLogic`.
- **`sharedLogic/`** — everything shared:
  - *Data*: Room entities, DAOs, database and seed (chat history, threads,
    roles); `SettingsManager` on multiplatform DataStore.
  - *Services*: `OAIService` (any OpenAI-compatible endpoint via the OpenAI
    client + Ktor), `LLMService` (engine abstraction — cloud or on-device
    Google LiteRT-LM, selectable per chat), `SpeechService` (STT/TTS
    abstraction with per-platform actuals), `MarkdownProcessor`.
  - *Source sets*: `commonMain` holds the logic; `androidMain`/`iosMain`
    provide `expect`/`actual` implementations (HTTP engine, storage, PDF).
- **`iosApp/`** — the SwiftUI shell, mirroring the Android feature surface
  (threads/messages, settings, onboarding, avatars, per-thread overrides,
  voice/video calls). It consumes the shared code through the static
  `SharedLogic` framework that Gradle builds for `iosArm64` and
  `iosSimulatorArm64` and Xcode embeds via the
  `embedAndSignAppleFrameworkForXcode` run-script phase; the entry point is
  `AppContainerFactoryKt.createAppContainer()`. On-device inference is real:
  a native Swift engine (`iosApp/iosApp/LiteRT/`) runs the same Google
  LiteRT-LM runtime as Android — both platforms are pinned to the same
  runtime version — sharing the Kotlin model catalogue and the same
  checkpoints.

# License

Released under the [Apache License 2.0](./LICENSE). Third-party library
licenses are listed in the app's acknowledgments screens.
