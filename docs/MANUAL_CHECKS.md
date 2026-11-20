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
| Store packs download | After the first push, wait for the "Store packs" workflow to finish (Actions tab), then on a phone: Settings › Store foods › Get on Kirkland Signature. Then scan a Kirkland barcode the built-in pack lacks (tools/build_store_pack.py prints counts). | The pack shows under On this phone with its product count; the scan finds the product in about 2 s with no "Checking Open Food Facts" line; the debug log has `scan_barcode` with `"store":"kirkland"`. Turn off Wi-Fi and data: Get fails with a plain message and nothing installed is lost. Automated: `StorePackViewsTest`, `StoreSuggestTest` (the HTTPS download itself has no JVM seam). |
| Scanning without scrolling | Scan › Label (or Auto until the label is seen), on a small phone and at 200% font size. | The camera and the Read now / That's all it lists buttons are all on screen at once; only the list of lines under the camera scrolls. Layout only: no JVM seam (owner, 11-19). |
| Watch: say and log | Paired Galaxy Watch, phone app installed. Watch: Say › "two eggs and toast" › Done. | Working it out, then the foods with kcal; Log it goes straight home with "Logged · Undo" for 5 s. The phone has the entry at the time it was said. Undo removes it on the phone. Automated: `WatchProtocolTest` (messages), `WatchListenerManifestTest` (phone can receive). The relay itself needs real hardware. |
| Watch: barcode, usual meal, phone away | Say "barcode" and a package's numbers; Log again › Your usual lunch; then turn the phone's Bluetooth off and Say something. | Found by barcode with its label numbers; the usual meal logs all its foods with one tap; with the phone away: Saved on watch, and the entry appears on the phone, at the time said, after Bluetooth is back and the watch app is opened. |
| Watch: debug mode | Phone in debug mode, use the watch for a minute, then save the phone's debug log. | `watch_*` events (screen, say_done, reply with ms) appear, with counts and timings only, never the words. With debug mode off, none. |
| Watch: tile and slots | Add the tile; on Ultra Info Board, put Nutrition Scribe in a small circle, the long bar and the big box, and Say in another circle. | Tile: main goal, a straight bar, Say opens listening. Slots show grams to go / "Protein 107 / 160 g"; Say opens listening. No rings drawn by the app. |
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
| Fixed by AI card | With a key: open an entry › Something's wrong? › type "it was two bowls" › Fix it. | The sheet closes and a dark green "Fixed by AI" card sits at the top of the entry: the change as chips, the before → after numbers (e.g. 420 → 840 kcal), Undo and Looks right. Undo puts the entry back and the card goes. Automated: `FixDiffTest` covers the numbers line. |
| Second look | Any food whose first answer's calories don't match its macros (see the debug log's `suspect` lines) | One more lookup call follows, with `reasoning_effort` medium. |
