# Protein Scribe — Implementation Blueprint

**Version 1.0** — Written September 11, 2026

---

## 1. Project Overview

Protein Scribe is a zero-friction, voice-first protein tracking app for Android with a Galaxy Watch 7 companion. Instead of searching a food database or tapping through menus, the user just says or types what they ate in plain conversational language — "I'm having a snack, it's a Built bar, it has 16 grams of protein" or "I'm eating four pieces of deli meat on this sandwich, this bread is from Walmart" — and an LLM parses the entry, fills in any nutrition data the user didn't already state, and logs it. Version 1 displays protein only, but the underlying data model captures full nutrition data from every lookup so calories and macronutrients can be turned on later without any rebuild. All data lives locally on the phone, fully under the user's control (view, edit, export, import, delete), with the watch acting as a fast input/glance surface that relays through the phone.

---

## 2. App Name

**Protein Scribe** — confirmed. Searched and clear as of September 2026:
- No existing app found under this name or "ProteinScribe"
- No GitHub repositories using this name
- Recommend a final manual check of the Play Store search bar before any future publish, since text search doesn't cover every listing

Fallback names considered, in case "Protein Scribe" needs to change later: Protein Pal, Macro Mouth, Gains Log.

---

## 3. Licensing & Distribution

- **v1 distribution: sideloaded APK.** This is a personal project; there is no requirement to publish anywhere. Build with Gradle, transfer the APK to the phone, enable "install from unknown sources" once. The watch app installs via its own APK, typically pushed through ADB or a companion install flow rather than the standard Play Store pairing process.
- **Future option: Google Play Store.** A one-time $25 developer registration fee, standard review process on every update. Worth doing later only if friends want the app too. Not part of the v1 build.
- **License:** no license needed for personal sideloaded use. If ever open-sourced or shared, MIT is a reasonable default (permissive, simple).
- **API key safety:** the user's LLM API key must never be bundled into the APK, committed to any repository, or logged in plaintext anywhere (including the debug log — see Section 7). Store it using Android's `EncryptedSharedPreferences` or the Jetpack Security library. The key is entered once during onboarding and used only for outbound API calls.

---

## 4. Core User Flow

**Phone — logging an entry:**
1. User taps the mic (primary action) or types in the Add Entry screen
2. Entry text is sent to the LLM along with a structured-output request
3. **Processing screen (v1 = simple wait-then-show):** a spinner shows while the app waits for the full LLM response. (A future v2 enhancement can upgrade this to live streaming steps — see Section 22.)
4. LLM returns structured JSON: `{ name, protein_grams, calories, macros, source, is_fixed_food }` — see Section 12 for the full schema
5. **Exception:** if the user explicitly stated the protein amount themselves ("Built bar, 16g protein"), the app skips the LLM lookup entirely for that value and uses the stated number directly. The LLM is still used to produce a clean short name for the entry, since that's essentially free as part of the same classification pass — no lookup is required though, so this case is worth designing as a lighter/faster path in the pipeline.
6. **Exception:** if the food is flagged `is_fixed_food: true` (packaged, exact-serving items like a specific protein bar) and this exact name has been logged before, the app reuses the cached nutrition data instead of calling the LLM again — instant and free. Variable foods (chicken breast, a sandwich) never use the cache, since portion and prep vary too much to trust a stored value.
7. Review screen shows the parsed food name and protein value, both editable, before confirming
8. On confirm, entry is saved locally, today's running total updates, and the home screen reflects it immediately
9. **On failure** (no network, API error, malformed response): the raw entry text is saved to an Unprocessed Queue with a visible error code and a retry button — nothing is ever silently lost

**Watch — logging an entry:**
1. Tap the mic on the watch home screen (or the Ultra Info Board complication)
2. Listening state: waveform animation, with pause / redo / cancel controls (handles interruptions mid-recording)
3. Processing state: transcribing + LLM lookup
4. Confirm screen: parsed food + protein grams shown, with "redo audio" and "edit on phone" buttons, plus haptic feedback (no text-to-speech)
5. Confirmed entries relay to the phone, which is the source of truth; the watch does not store data independently beyond a small local cache for fast cold-start rendering

---

## 5. Backends

| Provider | Model | Input cost | Output cost | Notes |
|---|---|---|---|---|
| OpenAI | GPT-5.6 Luna | $0.10–0.20 / 1M tokens | $0.60–1.20 / 1M tokens | Preferred for v1 — cheap enough that a typical day of logging costs a fraction of a cent |
| Anthropic | Claude API (e.g. Haiku-class model) | Comparable low-cost tier | Comparable low-cost tier | Fully supported as an alternate backend |

**Important:** the user's existing Claude Pro/Max subscription (or ChatGPT subscription) does **not** provide API access. A separate, pay-per-token API key must be generated from the provider's developer console for either backend. The onboarding screen must make this distinction explicit in plain language (see Section 10 copy).

**Backend architecture:** implement a pluggable AI backend interface from day one, even though only cloud APIs are used in v1. This is what makes a v2 local-LLM option (e.g. Ollama) a configuration change rather than a rewrite.

**Structured output contract** (every LLM lookup call must request this shape):
```json
{
  "name": "string — clean short display name",
  "protein_grams": "number",
  "calories": "number | null",
  "carbs_grams": "number | null",
  "fat_grams": "number | null",
  "is_fixed_food": "boolean — true for packaged/exact-serving items",
  "source": "string — e.g. 'USDA FoodData Central'",
  "confidence": "estimated | stated"
}
```
Calories and macros are captured and stored even though the UI only surfaces protein in v1.

---

## 6. Text Delivery / Output

- **Phone:** parsed results appear on the Review screen; confirmed entries appear immediately in the home screen's entry list and update the running total
- **Watch:** parsed results appear on the watch Confirm screen with haptic confirmation; no TTS
- **Widget/Tile/Complication:** read from a locally cached running total — never trigger a live network call on render, so they open instantly

---

## 7. Error Handling

| Failure case | User-facing message | Recovery action | Logged? |
|---|---|---|---|
| No network connection | "No connection. Err: NET_0012" | Entry saved to Unprocessed Queue; manual retry button; error code copyable | Yes — debug log |
| LLM API error (4xx/5xx) | "Couldn't process that. Err: API_0{status}" | Entry saved to Unprocessed Queue; retry button | Yes |
| Malformed/unparseable LLM response | "Couldn't understand the result. Err: PARSE_0001" | Entry saved to Unprocessed Queue; retry button | Yes |
| API key missing/invalid | "Check your API key in Settings. Err: AUTH_0001" | Deep-links to Settings → API key field | Yes |
| Watch: poor speech-to-text transcription | Confirm screen shows the (possibly wrong) parsed result | "Redo audio" or "Edit on phone" buttons | No (not an error, expected variance) |
| Watch: can't reach phone to relay entry | Local watch cache holds entry; syncs when reconnected | Automatic retry on reconnect | Yes |
| No entries yet (not a failure) | Empty state: "Log your first meal" | — | No |

All failures preserve the original raw entry text — nothing the user typed or said is ever discarded, only the processing of it is deferred.

---

## 8. Voice / Text Entry Triggers

- **Phone:** tap the floating add button on Home, or tap the mic icon directly on the Add Entry screen (mic is the large, primary visual action; typing is the secondary option underneath, per the user's own speech-first habits)
- **Watch:** tapping the mic on the watch home screen, or tapping the Ultra Info Board complication, both jump straight into the voice-listening state immediately — not an intermediate app-open screen. Speed is the entire point of the watch companion, so there is no extra tap between "glance at wrist" and "start talking."
- **Phone:** tapping the add button opens the Add Entry screen, where mic vs. type is a deliberate choice rather than an automatic action — appropriate since the user isn't already mid-motion the way they are on the watch.
- **No keyboard shortcuts / hotkeys** — this is a touch/voice-only mobile app, not a desktop utility

---

## 9. UI Components

**Phone:**
- Bottom navigation: Home / History / Settings (three tabs, fixed)
- Floating add button on Home
- "Needs attention" banner on Home when the Unprocessed Queue is non-empty
- Multi-select mode in History (tap "Select" to enter; checkboxes per row; bottom toolbar with Delete / Export / Cancel — modeled on the Android file picker / Gmail selection pattern)
- Zoomable history chart (see Section 12)

**Watch:**
- Home screen: mic button + today's protein ring
- Listening state: waveform + pause/redo/cancel
- Confirm state: check icon, parsed food + protein, redo/edit buttons
- Ultra Info Board complication, implemented as **both** `GOAL_PROGRESS` (progress bar) and `SHORT_TEXT` (icon + "118/160g" text) — the OS selects whichever type the target slot supports
- Tile using the `graphicDataCard` ProtoLayout pattern: tinted rounded card containing a small circular progress ring with the "Protein 118g of 160g" label inside the card (not floating above it)

---

## 10. First-Run Wizard

Three screens, minimal, under a minute to complete.

**Screen 1 — API key**
> **Add your API key**
> Protein Scribe uses your own key to look up nutrition info. It's not the same as a Claude or ChatGPT subscription.
> [Input: `sk-...`]
> *How do I get a key* (link/expandable help)
> [Continue]

**Screen 2 — Daily goal**
> **Set your daily goal**
> Grams of protein per day.
> [Input: `160`]
> [Continue]

**Screen 3 — Ready**
> **You're set**
> Type or speak what you ate.
> [Start logging]

---

## 11. Settings

All values are tap-to-edit inline (no separate "edit" screen needed for these).

| Setting | Control | Default |
|---|---|---|
| Daily protein goal | Tap to edit number, grams | 160 g |
| API key | Tap to edit, masked | — |
| Keep protein totals | Tap to edit: number + unit picker (days / weeks / months / years / indefinite) | 1 year |
| Keep food details | Tap to edit: number + unit picker (days / weeks / months / years / indefinite) | 3 months |
| Theme | System / Light / Dark | System |
| → Debug log | Navigates to log viewer | — |
| → Export and import | Navigates to data screen | — |

---

## 12. History / Data

### Entry schema (per logged item)
```
id: UUID
timestamp: datetime
raw_text: string          -- exactly what the user said or typed
name: string               -- LLM-generated clean display name
protein_grams: number
calories: number | null
carbs_grams: number | null
fat_grams: number | null
is_fixed_food: boolean
source: string              -- e.g. "USDA FoodData Central", or "user-stated"
confidence: "estimated" | "stated"
```

### Retention
- Two independently configurable windows: **protein totals** (default 1 year) and **food details** (default 3 months), both adjustable in days/weeks/months/years or indefinite
- When food details expire, only the raw text/name/source is dropped — the day's total protein contribution is preserved as long as the totals-retention window allows, so history charts stay accurate even after detail is gone

### Data efficiency
- Only transcript text is ever stored — no audio is retained past the point of transcription
- Local storage uses an indexed SQLite database (via Room) so search and chart queries stay fast even after a year of daily entries
- Entries older than the food-detail retention window are compacted (raw text and source dropped, protein total retained) rather than deleted outright, keeping storage small — text-only nutrition logs are inherently tiny; a full year of daily entries is expected to be a few megabytes at most even before compaction

### History screen — zoomable chart
- Chart shows protein data as connected dots (blue = under goal for that point, green = at/above goal), with a dashed reference line at the current goal
- **Continuous pinch-to-zoom**, not fixed day/week/month/year tabs. Implementation: an underlying daily-resolution dataset is always kept; a zoom level (0–100, or directly from a `ScaleGestureDetector` pinch scale factor) maps to a "days visible" window using an exponential curve (`daysVisible = 7 × (totalDays/7)^(zoom/100)`), so zooming feels smooth rather than stepped
- The visible window is automatically re-bucketed into roughly 10–12 display points based on `daysVisible`, averaging the underlying daily values within each bucket:
  - bucket size 1 day → daily points
  - bucket size 2–6 days → multi-day points
  - bucket size 7–27 days → weekly points
  - bucket size 28–299 days → monthly points
  - bucket size 300+ days → yearly points
- Implement the pinch gesture with Android's `ScaleGestureDetector` directly on the chart view, updating the zoom level continuously as the user pinches (not just on gesture end), so the regrouping animates smoothly in real time. A slider is not part of the shipped UI — pinch is the real interaction; the app may still show the current date range as text above the chart for orientation.
- Average value and "on track" / "building up" badge, best point, and goal-met count all recompute for whatever window is currently visible
- Tapping a point shows its exact date and value
- History multi-select (Section 9) works at the individual-entry list level, independent of the chart

### Per-meal reference
- Home screen shows the daily goal divided by 3 as a quiet secondary reference (e.g. "~53g per meal") alongside the main total, so the user has a rough per-meal target without doing math

---

## 13. Visual & Haptic Feedback

- **Theme:** follows system light/dark by default, overridable in Settings. All colors are defined for both modes (see palette below)
- **Color logic:** green = goal met/exceeded, blue = under goal. No amber or red anywhere in progress indicators — under-goal days should never read as a failure or scold
- **Home/history visual polish:** use subtle food photography or simple icon illustrations rather than plain white/empty backgrounds, to avoid the flat, sterile feel
- **Watch:** haptic pulse on entry confirmation; no sound, no text-to-speech

### Color palette — confirmed
| Role | Light | Dark |
|---|---|---|
| Background | `#FAFAF7` | `#17171A` |
| Surface | `#FFFFFF` | `#232326` |
| Border | `#E7E5DE` | `#2C2C30` |
| Text primary | `#1C1C1A` | `#F2F1EC` |
| Text secondary | `#6B6B66` | `#A8A79F` |
| Accent (goal met, green) | `#2F6F4F` | `#4E9873` |
| Under-goal (blue) | `#3E6FA8` | `#6D9BD1` |
| Danger (errors only) | `#B5433A` | `#D9776C` |

Dark mode is not a simple inversion — green and blue are both lightened and slightly desaturated from their light-mode values so they hold enough contrast against the near-black background without reading as neon.

---

## 14. File Structure

```
protein-scribe/
├── app/                              # Phone module
│   ├── src/main/java/.../
│   │   ├── data/
│   │   │   ├── db/                   # Room database, DAOs, entities
│   │   │   ├── repository/           # Single source of truth, shared with wear module
│   │   │   └── cache/                # Fixed-food cache logic
│   │   ├── network/
│   │   │   ├── backend/              # Pluggable AI backend interface
│   │   │   ├── openai/               # GPT-5.6 Luna implementation
│   │   │   └── anthropic/            # Claude implementation
│   │   ├── ui/
│   │   │   ├── onboarding/
│   │   │   ├── home/
│   │   │   ├── addentry/
│   │   │   ├── review/
│   │   │   ├── unprocessed/
│   │   │   ├── history/              # includes chart + pinch-zoom logic
│   │   │   ├── settings/
│   │   │   ├── debuglog/
│   │   │   └── exportimport/
│   │   └── widget/                   # Home screen widget provider
│   └── build.gradle.kts
├── wear/                             # Watch module
│   ├── src/main/java/.../
│   │   ├── ui/                       # Home, listening, confirm screens
│   │   ├── complication/             # GOAL_PROGRESS + SHORT_TEXT providers
│   │   └── tile/                     # graphicDataCard tile service
│   └── build.gradle.kts
├── shared/                           # Code shared between app and wear (data models, sync)
└── settings.gradle.kts
```

---

## 15. Dependencies

- **Room** — local SQLite database with indexing
- **Jetpack DataStore** — settings storage
- **Jetpack Security (EncryptedSharedPreferences)** — API key storage
- **Retrofit + OkHttp** — network calls to LLM APIs
- **Moshi or kotlinx.serialization** — JSON parsing for structured LLM responses
- **WorkManager** — background retry for the Unprocessed Queue
- **Jetpack Compose** — UI (phone and watch)
- **Wear OS Tiles & Complications APIs** — watch surfaces
- **Android SpeechRecognizer** — on-device/system speech-to-text for voice entry
- **Horologist** (optional reference) — confirmed as a legitimate Wear OS toolkit, not required but useful for media/voice UI patterns
- **CSV/JSON serialization** — for export/import (shared format both directions)

---

## 16. Build & Distribution

1. Standard Gradle multi-module build (`app` + `wear` + `shared`)
2. Build signed or debug APKs for both modules
3. **v1 distribution: sideload.** Transfer APK via USB/ADB, `adb install app-debug.apk`; enable unknown sources once on the phone. Watch APK installs via ADB over Wi-Fi debugging or a companion push, since normal Play Store pairing isn't available for sideloaded apps.
4. **Future (optional): Play Store.** $25 one-time developer fee, standard listing + review process. Not required for v1; revisit only if distributing beyond personal use.

---

## 17. Installer & Uninstaller

- No custom installer — standard Android APK install/uninstall
- Uninstalling the phone app removes the local Room database, cached settings, and API key (since it's stored in app-scoped encrypted storage)
- **Before uninstalling, the user should use Export (Section 12/19) to back up their data** — this should be called out in the app's own uninstall-adjacent messaging if feasible, and definitely in the README
- Uninstalling the watch app removes its local cache only; the phone remains the source of truth

---

## 18. Internationalization

English only for v1. Not a current priority. If revisited later, the structured UI text in Section 19 is already organized in a way that would translate cleanly to a key-value resource file (`strings.xml`) — no architectural changes needed to add a language later, just translated resource files.

---

## 19. Complete UI Text

**Onboarding**
- "Add your API key" / "Protein Scribe uses your own key to look up nutrition info. It's not the same as a Claude or ChatGPT subscription." / "How do I get a key"
- "Set your daily goal" / "Grams of protein per day."
- "You're set" / "Type or speak what you ate."
- Buttons: "Continue", "Start logging"

**Home**
- "Today"
- "~{n}g per meal" (goal ÷ 3, secondary reference text)
- "{n} entry needs attention" / "{n} entries need attention"
- "Log your first meal" / "Type or speak what you ate." (empty state)
- "Add entry" (empty-state button)

**Add Entry**
- "What did you eat"
- "Tap to speak"
- "or"
- "Type it instead"

**Processing**
- "Looking up nutrition"
- "Usually under 2 seconds"

**Review**
- "Parsed from your entry"
- "Estimated, tap to adjust" (shown only when `confidence: estimated`)
- "You said the amount, so no lookup was needed." (shown only when `confidence: stated`)
- "Confirm"

**Unprocessed Queue**
- "Needs attention"
- "No connection. Err: NET_0012" (and other error-code variants per Section 7)
- "Retry" / "Copy error"

**Entry Detail**
- "What you said" (label above the original raw text)
- "Save" / "Delete"

**History**
- "Search entries"
- "Select" / "Cancel" / "{n} selected" / "Select all"
- "Delete" / "Export" (bulk toolbar)
- "On track" / "Building up" (average badge states)
- "Best point" (or "Best day"/"Best week"/etc. depending on zoom granularity)
- "Goal met" / "{n} of {n}"

**Settings**
- "Daily goal" / "API key" / "Keep protein totals" / "Keep food details" / "Theme"
- "Debug log" / "Export and import"

**Debug Log**
- "Export log file" / "Copy to clipboard"

**Export/Import**
- "Format" / "CSV" / "JSON"
- "Export" / "Import"
- "Import accepts files exported from this app."

---

## 20. Recommended Build Order

Phone and watch are built together, per the user's preference — order below interleaves both rather than treating them as sequential phases.

1. **Data layer first:** Room schema (Section 12), repository, shared module structure between phone and watch
2. **Core phone loop, skeleton:** Home (with hardcoded/demo data) → Add Entry → Processing (wait-then-show) → Review → save to DB → Home reflects it
3. **Core watch loop, in parallel:** watch Home → Listening → Confirm → relay to phone via shared module
4. **LLM backend integration:** pluggable interface, GPT-5.6 Luna implementation, structured output parsing, the stated-value and fixed-food-cache shortcuts
5. **Error handling:** Unprocessed Queue, retry logic (WorkManager), error codes end to end
6. **History screen:** entry list, search, multi-select, then the zoomable chart with pinch gesture and bucketing logic
7. **Settings:** all fields editable inline, theme switching (system/light/dark), retention pickers
8. **Watch surfaces:** complications (`GOAL_PROGRESS` + `SHORT_TEXT`), tile (`graphicDataCard`), cold-start caching
9. **Debug log + export/import**
10. **Visual polish:** food imagery/icons on Home and History, final light/dark color pass, empty states
11. **Onboarding wizard** (can be built any time after the core loop works, since it just feeds Settings)

---

## 21. README Checklist

- What the app does and who it's for (one paragraph)
- Screenshot or short clip of the core logging flow
- How to build (Gradle commands, module structure)
- How to sideload to phone and watch
- How to get an API key (OpenAI or Anthropic), and the explicit note that a Claude/ChatGPT subscription does not include API access
- Where the local database lives and how to back it up (Export screen)
- Known limitations (Section 22, copied in)
- License

---

## 22. Known Limitations

- **v1 processing is wait-then-show, not live-streaming.** The "split into items / looking up X" step-by-step view and time-remaining estimate discussed during design are a v2 enhancement — they require the LLM call to stream, which is more fragile to get right. v1 shows a simple spinner; typical wait is under 2 seconds, which is unlikely to be noticeable.
- **Fixed-food caching only, in v1.** Packaged/exact items (protein bars, a specific shake) are cached and reused automatically. Variable foods (chicken breast, a sandwich) always get a fresh LLM estimate — no smart fuzzy-matching, portion confirmation, or correction-becomes-default behavior for variable foods yet. That's an intentional v2 scope.
- **English only.**
- **Requires the user's own paid API key** — there is no bundled or free backend. Costs are very low (fractions of a cent per day of typical logging) but are not zero.
- **No cloud backup or multi-device sync.** All data is local to the phone; Export/Import is the only way to move or back up data.
- **Watch speech-to-text quality is expected to be inconsistent** — this is a known platform limitation, not an app bug, which is why "redo audio" and "edit on phone" exist as first-class recovery paths rather than afterthoughts.
- **Calories and macronutrients are captured but not displayed** in v1 — the data model supports them from day one specifically so this is a UI-only change later, not a rebuild.
