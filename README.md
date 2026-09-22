# Nutrition Scribe

Zero-friction, voice-first protein tracking for Android with a Galaxy Watch 7 companion.
Say or type what you ate in plain language; an LLM parses it, fills in whatever nutrition
data you didn't state yourself, and logs it. All data stays local to your phone.

Full design spec: [`nutrition-scribe-blueprint.md`](./nutrition-scribe-blueprint.md).

## Screenshot / clip

_Not yet captured — add a screenshot or short clip of the core logging flow here once
the app is running on a device._

## Build

Standard Gradle multi-module project (`app`, `wear`, `shared`) with the wrapper checked
in. Both modules build clean (`./gradlew clean assembleDebug`) with:

- JDK 17 (Temurin 17.0.20.1+1)
- Gradle 8.4 / AGP 8.2.2 / Kotlin 1.9.22
- Android SDK Platform 34, Build-Tools 34.0.0, cmdline-tools build 15859902

Open the root folder in Android Studio (Hedgehog or newer) and let it sync, or from the
command line, with `ANDROID_HOME` pointed at an SDK that has the platform/build-tools
above installed:

```
./gradlew :app:assembleDebug
./gradlew :wear:assembleDebug
```

## Sideloading

**Phone:**
```
adb install app/build/outputs/apk/debug/app-debug.apk
```
Enable "install from unknown sources" once, if prompted.

**Watch:** pair Wi-Fi debugging on the Galaxy Watch 7 (Developer options), then:
```
adb connect <watch-ip>:<port>
adb install wear/build/outputs/apk/debug/wear-debug.apk
```

## API key

Nutrition Scribe calls the OpenAI or Anthropic API directly with your own key — this is
**not** the same as a ChatGPT Plus or Claude Pro/Max subscription, which do not include
API access. Generate a key from the provider's developer console:

- OpenAI: https://platform.openai.com/api-keys
- Anthropic: https://console.anthropic.com/settings/keys

Enter it during onboarding, or later in Settings → API key. It's stored on-device via
Jetpack Security's `EncryptedSharedPreferences` — never bundled, committed, or logged.

## Data & backup

All entries live in a local Room/SQLite database on the phone (`protein-scribe.db` —
kept under its pre-rename name so that existing logs are not stranded);
the watch only keeps a small cache for fast cold-start rendering. There is no cloud
backup or sync. Use Settings → Export and import to back up or move your data before
uninstalling.

## Known limitations

See Section 22 of the blueprint. In short: wait-then-show processing (not live
streaming) in v1, fixed-food caching only for packaged items, English only, requires
your own paid API key, no cloud backup, watch speech-to-text quality varies by design
(hence "redo audio" / "edit on phone"), and calories/macros are captured but not shown.

## Implementation status

The full architecture and core flows are wired up (data layer, both AI backends, phone
loop, watch loop, error handling/retry, history chart, settings, debug log,
export/import, onboarding). **Both `app` and `wear` compile and package cleanly** — a
clean `./gradlew assembleDebug` succeeds end to end and produces both debug APKs. That
only proves it compiles and packages, not that it behaves correctly: none of it has been
installed on a device or emulator yet, so the UI flows, speech recognition, watch relay,
and complications/tile still need to be exercised on real hardware (or an emulator +
paired Wear emulator) before trusting them. Not yet done: food photography/icons on Home
and History, and the retention-window Settings rows are currently display-only (not yet
editable pickers).

## License

No license needed for personal sideloaded use. If ever shared or open-sourced, MIT is a
reasonable default.
