# Nutrition Scribe — notes for Claude Code

Android phone app with a Wear OS companion. Gradle modules `app`, `wear`, `shared`.
Kotlin 2.0.21, AGP 8.9.2, Gradle 8.11.1 (wrapper), JDK 17, compileSdk 36.
What the app does: [README.md](README.md). Setting up a machine: [docs/SETUP.md](docs/SETUP.md).
Design spec: [nutrition-scribe-blueprint.md](nutrition-scribe-blueprint.md).

## Commands

```bash
# JVM tests (seconds; no device). Expect shared 220 (9 skipped), app 44.
./gradlew -Dorg.gradle.jvmargs=-Xmx2g :shared:testDebugUnitTest :app:testDebugUnitTest
# The label-pack test runs only when given an unzipped pack:
#   gunzip -c app/src/main/assets-pack/labels.db.gz > /tmp/labels.db
#   ./gradlew -DlabelPack=/tmp/labels.db :shared:testDebugUnitTest --tests '*MicronutrientSourcesTest*'

# Accuracy benchmark: entries through the real Resolver + AI, then pair, judge and score.
# The prompt, truth and result files hold food logs and live outside the repo. --rerun is
# needed because Gradle caches the test task. One AI call per entry (~$0.06 per 300).
OPENAI_API_KEY=$(cat ~/.config/nutrition-scribe/openai-key) ./gradlew -Dorg.gradle.jvmargs=-Xmx2g \
  :shared:testDebugUnitTest --rerun --tests '*BenchRunTest*' -Dbench=P.jsonl -DbenchOut=R.jsonl \
  -DlabelPack=/tmp/labels.db -DpersonalFoods=my_foods.jsonl
python3 tools/bench_pairs.py --prompts P.jsonl --truth T.jsonl --run R.jsonl --out pairs.jsonl  # graders correct this
python3 tools/bench_score.py --prompts P.jsonl --truth T.jsonl --judged judged.jsonl --run name=R.jsonl --out scores.json

# Release APKs, signed when keystore.properties exists (unsigned otherwise)
./gradlew -Dorg.gradle.jvmargs=-Xmx2g :app:assembleRelease :wear:assembleRelease

# Instrumented tests: emulator only (they uninstall the app, wiping its data)
./gradlew :app:connectedDebugAndroidTest

# After a batch of builds
./gradlew --stop
ps aux | grep "[K]otlinCompileDaemon"   # then kill that PID
```

Never use `pkill -f` or `pgrep -f` inside a command chain. The pattern matches the
shell's own command line and the shell kills itself (exit 144).

## Working rules (the owner's standing instructions)

- **Questions:** ask clarifying questions with the AskUserQuestion tool, never as a numbered list in chat.
- **Commits:** commit or push only when asked.
- **The repo is public.** Nothing personal or secret goes in files, test fixtures or commit messages.
  - No names, emails, device serials, body stats, food logs or keys.
  - No claude.ai session or artifact links.
- **RAM** (relaxed 2026-09-30 for this 31 GB desktop; the old laptop had 14 GB):
  - Building while one emulator runs is fine.
  - Keep Gradle at 4 GB or less and the emulator at `-memory 4096` or less.
  - Don't leave emulators or Gradle and Kotlin daemons running once a batch of work is done.
- **The owner's phone:** never run instrumented tests on it. Install over the existing app
  (`adb install -r`) so its data is kept.
- **The OpenAI key** lives at `~/.config/nutrition-scribe/openai-key`.
  - Load it only into one command's environment, e.g. `OPENAI_API_KEY=$(cat ~/.config/nutrition-scribe/openai-key) python3 tools/...`.
  - Never print, commit or copy it.
  - Never bake any key into an APK. Testers type a restricted key in at setup.
- **Signing:**
  - Never commit `keystore.properties` or `*.jks`.
  - Never generate a new keystore. `~/keystores/protein-scribe.jks` is the only key updates can be signed with.
  - The release certificate's SHA-256 is `989f0462a197bb10b052e941485743837888b94f95384050e0d5725ccedace2f`.
- **Names that must never change:**
  - applicationId `com.nutritionscribe`
  - database `protein-scribe.db`
  - DataStore `protein_scribe_settings`
  - `protein_scribe_secure_prefs`

  Renaming any of them strands existing users' data.
- **Personal data:**
  - Exports (`*-export*.json`), `tools/.cache/` and testers' debug-log zips are gitignored and read-only. Never commit them.
  - Never send the owner's email to any service. The Open Food Facts User-Agent carries no email.
- **Handing out an APK:** bump both `versionCode` and `versionName` in `app/build.gradle.kts` for every build handed out.

## Gotchas

- **SQLite:** Android's SQLite has no FTS5. Search uses a hand-built `word_index`; don't reintroduce FTS5.
- **`food.db`:** after replacing `app/src/main/assets/food.db`, bump `assetVersion` in `FoodDatabase.kt`,
  or installed copies keep the old data.
  The More to track columns (copper … alcohol) were added in place by `tools/add_extras_food_db.py`
  from SR Legacy 2018-04 and FNDDS 2022-10-28; bump `MicroBackfill.VERSION` too when foods gain nutrients.
- **Paired code (Python and Kotlin):**
  - `tools/foodsearch.py` must match `FoodQuery.kt`; `FoodQueryParityTest` checks this.
  - `build_label_pack.words_of` must match `LabelPackSource.words()`.
- **Label pack:**
  - It sits in `app/src/main/assets-pack/` (gitignored): `labels.db.gz` plus `labels.id` ("format|since|products").
  - AGP unpacks the `.gz` into `assets/labels.db`. `LabelPack.installBundled()` tries both names and
    only replaces a missing or smaller pack.
  - Builds without the folder work; they just ship no pack.
- **Room:** schema JSONs live in `app/schemas/` (v1 was reconstructed by hand). Migration tests need them.
- **Emulator won't start:** a corrupt snapshot (`KVM: entry failed, hardware error 0x80000021`) → cold boot with `-no-snapshot-load`.
- **Seeding the emulator:** `run-as com.nutritionscribe sqlite3 databases/protein-scribe.db < seed.sql`.
  Tables are `entries`, `entry_nutrient` and `metric_goal`.
- **Watch relay:** the phone↔watch relay has never been tested. Don't claim it works.
- **R8:** it is off on purpose. Room, Moshi and serialization keep rules are untested.

## Files that live outside the repo

These travel between machines in a private archive, never through GitHub. See docs/SETUP.md.

| Where | What |
|---|---|
| `keystore.properties` (repo root) | Signing config and passwords |
| `~/keystores/protein-scribe.jks`, `protein-scribe-keystore-info.txt` | Release keystore |
| `local.properties` (repo root) | `sdk.dir=`: written per machine |
| `app/src/main/assets-pack/` | Bundled label pack (22 MB) |
| `tools/.cache/` | Benchmark caches built from the owner's real log |
| `*-export*.json` (repo root) | The owner's real food log, the input for `tools/bench_*` |
| `~/.config/nutrition-scribe/openai-key` | OpenAI API key (short-lived; get a new one) |
| `~/.cache/fdc-bench/` | USDA source data, 4.1 GB. Only needed to rebuild the data |
| `~/.cache/ns-vision/` | llama.cpp and local vision models, only for `tools/bench_portion_photo.py` |

## Tools used on this project

**Toolchain** (all installed in the home directory, no root): Temurin JDK 17.0.20.1+1.

Android SDK:
- cmdline-tools
- platform-tools 37.0.1
- emulator 37.1.11
- platforms 34 and 36
- build-tools 34, 35 and 36
- system images `android-34;google_apis;x86_64` and `android-34;android-wear;x86_64`

The NDK 26.3 and CMake 3.22.1 are installed but the build doesn't declare them.

AVDs:
- `A34`: a Galaxy A34-like phone, 1080×2340, 450 dpi, 3 GB
- `Wear34`: small round watch, 1 GB

Python 3 with the standard library only (every script in `tools/`). `sqlite3` (the SDK's
`platform-tools` has one).

**Claude Code:**
- **Shell and files:** Bash, Read, Edit and Write for everything in the repo.
- **Devices:** the emulator and phone are driven through `adb` (`screencap`, `input tap/text`,
  `run-as`, `logcat`) for UI checks.
- **Questions:** AskUserQuestion for every decision.
- **Research:** WebSearch and WebFetch, for DRI tables, FDA plasma rules, and AI providers' data policies.
  Re-check the policies before changing `AiConsent.kt`; they must match what the providers say.
- **Reports:** the Artifact tool, with the `artifact-design`, `artifact-capabilities` and `dataviz` skills.
  It publishes private report and decision pages on claude.ai, and ArtifactData holds saved decisions.
- **AI features:** the `claude-api` skill, consulted when building them. The app calls the OpenAI and
  Anthropic HTTP APIs itself through Retrofit and OkHttp, not SDKs.
- **Subagents** (Agent tool): only occasionally.
- **Memory:** file-based, in `~/.claude/projects/<project-key>/memory/`. It carries the owner's
  preferences and project history, and travels in the private archive.
