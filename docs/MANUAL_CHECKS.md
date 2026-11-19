# Manual checks

Fixes that no JVM or instrumented test can see. They are mostly about how the
system lays out a window. Run each one on the emulator (or a phone running
Android 15 or later) before handing out a build that touches it.

| Check | How | Pass |
|---|---|---|
| Save above the keyboard | Open any entry › tap a food › tap a nutrient box so the keyboard opens. | The Save bar sits just above the keyboard, not under it. |
| Shake to report | On a real phone, shake it two or three times on Home, on a sheet, and with the check card open. Then Settings › Beta testing › switch "Shake to report a problem" off and shake again. | "What's wrong here?" opens each time with the screen's name; nothing opens while the mic listens, while walking, or when switched off. On the emulator: `adb emu sensor set acceleration 0:0:30` then `0:0:9.8`, three times within a second. |
| Pages scroll above the keyboard | Settings › Beta testing › Something's wrong? Tell us; tap the note box. Also the scan form's number boxes. | With the keyboard up, the page scrolls down to Save / Use these. |
| Large text | Android Settings › Display › Font size at the largest (200%); look at Home, Settings and the dock. | Nothing is cut off or one letter a line; rows stack, the dock reads Say / Scan / Type / Ask. |
| Edge to edge, Android 15+ | AVD `A35` (API 35), three-button nav: `adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton`. Open the food editor (Change on a check card), Tell us, setup, Home. Light theme too. | Done / Save above the nav buttons; status-bar clock dark on the light theme. Automated: `FoodEditorBarsTest` (instrumented, fails on API 35 without the dialog fix). |
| Entry-page scans stick | Open a logged entry › tap a food › Scan its label (barcode). As soon as it comes back, tap the same food › Scan its label again and scan a different product's label. | Each scan replaces the food it was scanned for, and the first one isn't lost or put back. The view model's saves are NonCancellable and serialised; there's no JVM seam for the Compose effect. |

## With a live API key

These change what the AI is asked, so only a real call shows whether they work.
`LiveChecksTest` runs them (and more) through the real resolver and stops at a budget;
a full run costs about $0.10:

```bash
OPENAI_API_KEY=$(cat ~/.config/nutrition-scribe/openai-key) ./gradlew -Dorg.gradle.jvmargs=-Xmx2g :shared:testDebugUnitTest \
  --rerun --tests '*LiveChecksTest*' -Dlive=/path/out.txt -DlabelPack=/path/labels.db -DliveBudget=0.5 [-DliveOnly=2,10]
```

On the phone, run them with debug mode on, then read the entry's AI calls.

| Check | Say or type | Pass |
|---|---|---|
| A dish by its parts | "a fruit salad that has some pineapple, grapes, apples and blackberries" | About 150 to 250 kcal in all, not 1 oz of each fruit. |
| A bag is an amount | "a sandwich bag of baby carrots, celery sticks, snap peas and broccoli" | Four vegetables, no "sandwich". |
| A sauce named first | "Alfredo Florentine with 2 ricotta stuffed shells" | The sauce is its own line. |
| Web search, when unsure | A local restaurant's menu item, by name and place | The card says "Found online", and AI spending shows a web search. |
| Web search off | The same item with Settings › AI › Search the web off | No web search in AI spending, and an AI estimate on the card. |
| Second look | Any food whose first answer's calories don't match its macros (see the debug log's `suspect` lines) | One more lookup call follows, with `reasoning_effort` medium. |
