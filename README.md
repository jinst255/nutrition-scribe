# Nutrition Scribe

Say or type what you ate, in whatever words you'd actually use, and it gets logged.

> "2 eggs and toast" · "half a cup of cottage cheese" · "20g protein shake"

Android phone app with a Wear OS companion. Free, open source, no ads, no accounts,
no tracking, no subscription. **Nothing you eat leaves your phone.**

Full design spec: [`nutrition-scribe-blueprint.md`](./nutrition-scribe-blueprint.md).

## Two ways to look food up

**Offline (no key, no network, no cost).** ~13,400 USDA foods with nine nutrients and
real household portions are built into the app, about 6 MB. Lookups take around 50 ms
on a device. This is the default when no API key is set, and you can force it in
Settings → "Look up nutrition with" → Offline food list.

**AI (your own API key).** Understands brands, restaurant meals and whole rambling
sentences. Needs a key from
[OpenAI](https://platform.openai.com/api-keys) or
[Anthropic](https://console.anthropic.com/settings/keys) — note these are developer API
keys, **not** a ChatGPT Plus or Claude Pro subscription, which do not include API
access. The key is stored on-device with `EncryptedSharedPreferences` and is never
bundled, committed or logged.

### How much worse is offline, honestly?

Measured against a real exported log of 72 dictated entries, scored against what the AI
answered for the same text (`tools/bench_real_log.py`):

| | offline vs AI |
|---|---|
| protein *and* calories within 40% | 54% |
| right food found (`tools/bench_friends.py`) | 100% |
| calories plausible for the portion | 87% |
| flags its own errors | 36% |

Offline is genuinely good at plain food — "2 eggs" comes back 143 kcal against the AI's
144. It is bad at four things, and no amount of ranking fixes them:

- **Brands and restaurants.** There is no Built Bar, no Baconator, no Chipotle portion
  in USDA. (There *is* a Big Mac, and it matches to within 1%.)
- **Compound dishes**, where one component gets lost: "falafel wrap" logs the falafel.
- **Portion judgement.** USDA's serving is not a restaurant's serving.
- **Understanding a sentence.** "the black currant preserve PB&J" is a PB&J to a human
  and a bowl of currants to a word index.

When it is unsure it says so rather than inventing a number — that is what the 36% row
means, and it is the number most worth improving.

## Build

Gradle multi-module project (`app`, `wear`, `shared`) with the wrapper checked in.

- JDK 17 (Temurin 17.0.20.1+1)
- Gradle 8.11.1 / AGP 8.9.2 / Kotlin 2.0.21
- Android SDK Platform 36 (and 34 for the watch), Build-Tools 36.0.0

Setting up a new machine, including the files that are deliberately not in this repo:
[`docs/SETUP.md`](./docs/SETUP.md).

```
./gradlew :app:assembleDebug
./gradlew :wear:assembleDebug
```

Keep the Gradle heap at or below 2 GB (`org.gradle.jvmargs=-Xmx2048m`) if you are also
running an emulator; the two together will otherwise get one of them OOM-killed.

## Tests

```
./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest   # JVM, seconds
./gradlew :app:connectedDebugAndroidTest                     # needs a device
```

The instrumented ones are not optional. Android's system SQLite is **not** desktop
SQLite — it ships without the `fts5` module, so the entire search had to be built on a
hand-rolled inverted index, and that difference only ever shows up on a device.

## Sideloading

```
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Watch:** pair Wi-Fi debugging on the watch (Developer options), then
`adb connect <watch-ip>:<port>` and install `wear/build/outputs/apk/debug/wear-debug.apk`.

## The offline food database

Built from USDA FoodData Central (public domain): SR Legacy plus FNDDS Survey.

```
python3 tools/build_food_db.py <dir-with-unzipped-FDC-csvs> -o food.db
python3 tools/curate_food_db.py food.db
cp food.db app/src/main/assets/food.db
```

Then **bump `assetVersion` in `FoodDatabase.kt`**, or installed copies keep reading the
old data and the update changes nothing.

`tools/` also holds the Python reference implementation (`foodsearch.py`) and three
benchmarks. The reference and `FoodQuery.kt` must stay in step — `FoodQueryParityTest`
checks that, because when they drifted apart once, the stemmer turned "pancakes" into
"pancak" and no search for "pancake" could reach it, silently, with no error anywhere.

| tool | what it measures |
|---|---|
| `bench_food_db.py` | ranking and sense on curated phrases, plus speed |
| `bench_real_log.py` | a real exported log against the AI answers stored in it |
| `bench_friends.py` | harder input from people who are not the author |
| `compare_ai_offline.py` | head-to-head against a live API (needs a key) |

Exported logs are personal data. They are gitignored, and the harnesses only read them.

## Data & backup

Everything lives in a local Room database on the phone (`protein-scribe.db`, kept under
its pre-rename name so existing logs are not stranded). The watch keeps a small cache
and a queue of entries it could not yet hand over. There is no cloud, no sync, no
account. **Settings → Export** before you uninstall anything.

## Known limitations

- Phone↔watch relay is unverified — it needs two paired devices, and only standalone
  watch operation has been tested (Wear OS 5 emulator, API 34).
- Edge-to-edge layout is unverified on Android 15+.
- No way yet to correct a wrong offline match by picking from a list, and corrections
  are not remembered.
- English only.
- Processing is wait-then-show, not streaming.

## License

MIT — see [`LICENSE`](./LICENSE).
