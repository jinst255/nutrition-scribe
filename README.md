# Nutrition Scribe

Say or type what you ate, in whatever words you'd actually use, and it gets logged.

> "2 eggs and toast" · "half a cup of cottage cheese" · "20g protein shake"

Android phone app with a Wear OS companion. Free, open source, no ads, no accounts,
no subscription. Your log lives on your phone. **Nothing you eat leaves it unless you
turn on AI lookups with your own key** (see [What leaves your phone](#what-leaves-your-phone)).

Full design spec: [`nutrition-scribe-blueprint.md`](./nutrition-scribe-blueprint.md).

## What it does

- **Log by voice or text.** Hold Say, or type. Live matches as you type, "Log as typed",
  a time chip for meals you forgot, and Log again for repeats. "My usual X, but…" finds
  the saved meal and changes it.
- **Check before it counts.** Anything the app hasn't seen you confirm is shown before
  it is logged. Once you confirm a food it goes into My foods and logs silently next time.
- **Scan.** Barcode, front of pack or the Nutrition Facts panel, read on the phone with
  ML Kit. A product's other flavours ("kinds") are offered in the same amount.
- **Targets that fit you.** Daily needs from the DRI tables and your saved body stats
  (age, height, weight, activity, aim, pregnancy or nursing), or your own numbers.
  Colours show over the target, past your limit, or too low.
- **More than macros.** Calories, protein, carbs, fat, fibre, sugar (natural and added),
  saturated fat, sodium, caffeine, cholesterol, and minerals and vitamins. More to track
  (omega-3, ALA, copper, manganese, phosphorus, choline, B1/B2/B3/B6, alcohol) is off by
  default and marked ≈ because it is estimated from the food list.
- **Plasma donors.** A donation's protein (about 44–56 g, by body weight) is added back
  over that day and the next, and the button refuses dates a centre would refuse.
- **History.** A bar per day with filters and ranges, and the foods behind any nutrient.
- **Ideas and Ask.** Foods that would close a gap, drawn from what you already eat, and a
  chat for questions. A picked idea is a plan, never a log entry.
- **A logging streak.** The day ends at 3 AM, and rest days don't break it.
- **AI spending.** With a key, every call is costed at its model's price and charted.
- **Export and import** as JSON or CSV.

## How a food is looked up

Four layers, tried in order (`shared/.../resolve/Resolver.kt`):

1. **Your own confirmed foods.** A hit logs without asking.
2. **The bundled data.** About 13,400 USDA foods (SR Legacy and FNDDS) with real
   household portions, about 8 MB, built into the app. Release builds can also carry
   a label pack of about 230,000 packaged products from USDA Branded Foods (22 MB).
   Lookups take around 50 ms and need no network.
3. **Help when those are weak:** a scan for packaged food, or breaking a homemade dish
   into its ingredients. A barcode the pack doesn't have is looked up in Open Food Facts.
4. **AI, with your own key.** Understands brands, restaurant meals and whole rambling
   sentences. OpenAI (GPT-6 Luna by default) or Anthropic (Claude Haiku 5.5 by default),
   with the model picked per job under Advanced › AI models.

AI needs a developer API key from
[OpenAI](https://platform.openai.com/api-keys) or
[Anthropic](https://console.anthropic.com/settings/keys). A ChatGPT Plus or Claude Pro
subscription does not include API access. The key is stored on the phone with
`EncryptedSharedPreferences` and is never bundled, committed or logged. Setup checks a
pasted OpenAI key with a free call, so a typo shows there rather than as a failed lookup.

### How good is the built-in food list on its own?

Measured on an earlier version against a real exported log of 72 dictated entries,
scored against what the AI answered for the same text (`tools/bench_real_log.py`).
That was before My foods, scanning and the label pack, so this is the floor:

| | offline vs AI |
|---|---|
| protein *and* calories within 40% | 54% |
| right food found (`tools/bench_friends.py`) | 100% |
| calories plausible for the portion | 87% |
| flags its own errors | 36% |

Plain food is fine: "2 eggs" comes back 143 kcal against the AI's 144. It is bad at four
things that no ranking fixes:

- **Brands and restaurants.** There is no Built Bar, Baconator or Chipotle portion in
  USDA. (There *is* a Big Mac, and it matches to within 1%.) The label pack and scanning
  cover packaged food; restaurants still need AI.
- **Compound dishes**, where one part gets lost: "falafel wrap" logs the falafel.
- **Portion judgement.** USDA's serving is not a restaurant's serving.
- **Understanding a sentence.** "the black currant preserve PB&J" is a PB&J to a person
  and a bowl of currants to a word index.

## What leaves your phone

| When | What is sent | Where |
|---|---|---|
| AI lookups on (your key) | The words of the entry | The provider you picked |
| Highest quality speech on | The recording of a spoken entry | OpenAI transcription |
| Ask | Your question and the foods and targets it needs | The provider you picked |
| A barcode the label pack doesn't have | The barcode number only | Open Food Facts |

Without a key, only that last one happens. Before each AI provider is used for the first
time, the app shows what it sends and what that provider's data policy says.
Speech without Highest quality goes through Android's own speech recognizer.

## Beta builds

Builds whose version name contains `test` (now `1.6-test`) are for testers:

- **First open:** the tester's name, the test week in three lines, a practice
  press-and-hold report, what leaves the phone, and the key step (with a "no key" path).
- **Press and hold** anything that looks wrong to save a note with a screenshot. Notes
  stay on the phone until the tester sends their log.
- **Settings › Beta testing:** the Tester guide (day N of 7, setup, the day-7 questions),
  ideas, what has been sent, and Send my log.
- **Day 7:** three questions, then "What's in your log": what always goes, and the
  personal parts (body stats, activity and aim, plasma days, label photos), ticked by
  default and free to untick. The API key is never in it.

How a round runs: [`docs/BETA_TESTING.md`](./docs/BETA_TESTING.md).

## Build

Gradle multi-module project (`app`, `wear`, `shared`) with the wrapper checked in.

- JDK 17 (Temurin 17.0.20.1+1)
- Gradle 8.11.1, AGP 8.9.2, Kotlin 2.0.21
- Android SDK Platform 36 (and 34 for the watch), Build-Tools 36
- Phone: Android 8.0 (API 26) and up. Watch: Wear OS 3 (API 30) and up.

Setting up a new machine, including the files that are deliberately not in this repo
(signing key, label pack): [`docs/SETUP.md`](./docs/SETUP.md).

```
./gradlew -Dorg.gradle.jvmargs=-Xmx2g :app:assembleDebug :wear:assembleDebug
./gradlew -Dorg.gradle.jvmargs=-Xmx2g :app:assembleRelease :wear:assembleRelease
```

Release builds are signed when `keystore.properties` exists and unsigned otherwise.
Without `app/src/main/assets-pack/` the build still works; it just ships no label pack.
Keep the Gradle heap at about 2 GB if an emulator is running too.

## Tests

```
./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest   # JVM, seconds
./gradlew :app:connectedDebugAndroidTest                     # emulator; wipes the app's data
```

The instrumented tests are not optional. Android's SQLite is **not** desktop SQLite: it
ships without the `fts5` module, so search is built on a hand-rolled word index, and
that difference only ever shows up on a device.

## Sideloading

```
adb install -r app/build/outputs/apk/release/app-release.apk
```

`-r` installs over the existing app and keeps its data. Updates must be signed with the
same key, so never uninstall to update.

**Watch:** pair Wi-Fi debugging on the watch (Developer options), then
`adb connect <watch-ip>:<port>` and install `wear/build/outputs/apk/release/wear-release.apk`.

## The data and the tools

The food list is built from USDA FoodData Central (public domain):

```
python3 tools/build_food_db.py <dir-with-unzipped-FDC-csvs> -o food.db
python3 tools/curate_food_db.py food.db
python3 tools/add_extras_food_db.py <same-dir> food.db   # the More to track columns
cp food.db app/src/main/assets/food.db
```

Then **bump `assetVersion` in `FoodDatabase.kt`**, or installed copies keep the old data.
When foods gain nutrients, bump `MicroBackfill.VERSION` too. The label pack comes from
`tools/build_label_pack.py`.

`tools/foodsearch.py` is the Python reference for `FoodQuery.kt`, and the two must stay
in step. `FoodQueryParityTest` checks that, because when they drifted apart once, the
stemmer turned "pancakes" into "pancak" and no search for "pancake" could reach it,
silently, with no error anywhere.

| tool | what it measures |
|---|---|
| `bench_food_db.py` | ranking and sense on curated phrases, plus speed |
| `bench_real_log.py` | a real exported log against the AI answers stored in it |
| `bench_friends.py` | harder input from people who are not the author |
| `compare_ai_offline.py` | head-to-head against a live API (needs a key) |
| `BenchRunTest` + `bench_pairs.py` + `bench_score.py` | entries through the real Resolver and AI, scored against package labels |

Exported logs are personal data. They are gitignored, and the tools only read them.

## Data and backup

Everything lives in a local Room database on the phone (`protein-scribe.db`, kept under
its pre-rename name so existing logs are not stranded). The watch keeps a small cache
and a queue of entries it could not hand over yet. There is no cloud, no sync and no
account. **Settings › Export** before you uninstall anything.

## Known limitations

- The phone↔watch relay is untested: it needs two paired devices, and only the watch on
  its own has been tried (Wear OS 5 emulator, API 34).
- Edge-to-edge layout is unverified on Android 15 and up.
- English only.
- R8 is off, so release APKs are large (about 50 MB with the label pack).

## License

MIT, see [`LICENSE`](./LICENSE).
