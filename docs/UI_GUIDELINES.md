# UI guidelines

Every new screen, and every screen that changes, follows this page, so the app looks
like one product and stays quick on a mid-range phone. When the code and this page
disagree, fix the code or change this page in the same commit; never let them drift.

The colours, type and shapes live in `app/.../ui/theme/Theme.kt`. Use them by role
(`MaterialTheme.colorScheme.primary`, `typography.titleMedium`); never write a hex
colour or a font size into a screen.

## 1. What the tap data says (Nov 2026, about 17,000 logged events)

These numbers decide what goes where. Re-check them when a beta log comes in.

| Finding | What it means for the UI |
|---|---|
| Voice logs in a median 24 s, typing 38 s, scanning 28 s | Hold to say stays the biggest, leftmost button on Home |
| 86% of Home views stay on Macros | Goals first; everything else is one line that opens a list |
| History → a nutrient chart is the commonest move there | Charts are one tap from History, never buried |
| Log again: 108 taps plus 52 on More | Repeats sit one tap away, just above the dock |
| 28% of scans abandoned; front read gave up 35 times | Every camera screen offers another way in (type, pick a photo) |
| A barcode miss costs a 44 s label read | Store packs and the "you buy a lot of X" suggestion |

## 2. Layout

- **Spacing:** one 4 dp grid. Screen side margin is 16 dp. Gaps inside a card are 8 or 12 dp,
  and gaps between cards are 12 dp. Put spacing in `Arrangement.spacedBy`, not in per-item padding.
- **Cards:** `surface`, a 1 dp `outlineVariant` border, `shapes.medium` (16 dp) corners and
  14–16 dp inner padding. One card style everywhere (V1). Never a coloured left border.
- **Lists:** rows sit inside one card with hairline dividers between them, not one card
  per row. A row is at least 56 dp tall (64 dp with a second line).
- **Sheets over dialogs:** a choice among more than two options, or anything with a text
  field, opens a bottom sheet. Dialogs are only for yes/no confirmations.
- **Bottom-anchored actions:** the screen's main action sits at the bottom, inside a bar
  pinned to the bottom (Log it, Use this, Done). A destructive action never takes the main slot.
- **Nothing hides behind a long press.** Every action shown has a visible way in; long
  press is only a shortcut.
- **Hiding a card closes its gap.** Never leave an empty slot where a dismissed card was.

## 3. Type

- Public Sans for words and IBM Plex Mono (`NumberStyle`) for amounts, so columns line up.
- Use the Material type roles from `AppTypography` (its `labelSmall` is 12 sp, not Material's 11).
  The minimum size is 12 sp (A3); body text is 15–16 sp; section labels use `SectionLabelStyle`
  (11.5 sp caps). The one other exception: tick labels drawn inside a chart, 11 sp.
- Write what a person would say: "Stay under", "Reach", "Log it", "Something's wrong?".
  No system words ("entity", "resolve", "candidate"). Limits always read "Stay under".

## 4. Colour

| Role | Token | Meaning |
|---|---|---|
| Main action, met goal | `primary` (green) | "Do this", "in range" |
| Reach-goal progress | `tertiary` (blue) | still climbing toward a target |
| Over a limit | `LimitColors.over` / `past` (sand / clay) | information, not alarm |
| Errors, "looks off", Delete | `error` | something is actually wrong |

Never tell two things apart by hue alone: change lightness too, or add a label or icon.
Both themes are designed; check dark mode on every new screen.

## 5. Touch and access (A1, A2)

- Every tappable thing is at least 48 × 48 dp. Main buttons are 52–56 dp tall.
- Every icon-only button has a `contentDescription`. Charts and tab rows get a
  `semantics { contentDescription = … }` that says the numbers in words.
- Must work at 200% font size on a 360 dp-wide screen (F9). Text wraps; it never clips.

## 6. Speed

- No work on the main thread that touches the database, a file or the network.
- A list longer than one screen is a `LazyColumn` with stable `key`s.
- A screen opens with what it has at once and fills in the rest. Never block it on the AI;
  show its progress where the answer will appear (Ask's streamed status is the model).
- No animation longer than 300 ms. Respect "Remove animations" in system settings.

## 7. Data handling on screen

- Show where a number came from (label, USDA, AI estimate, store pack) wherever it can be changed.
- Every destructive action can be undone for a few seconds (Snackbar + Undo) or asks first.
- Credit open data where it is used. Open Food Facts data shows "© Open Food Facts
  contributors, ODbL" on the Store foods screen and in About.
- Nothing personal in logs that leave the phone without the person seeing it first.

## 8. The watch (Galaxy Watch 7, Wear OS)

- Black background (AMOLED), one centred column: the time on top, then at most three things.
- **No arcs or rings around the edge.** Progress is a short straight bar: blue for
  reach goals, green for stay-under. The owner found curved edge bars confusing (11-19).
- Nothing overlaps; nothing sits outside the circle's safe area (inset about 15%).
- Buttons are plain pills inside the screen: the main one green and 84 px tall, others dark
  and 68 px. **No edge button** (Wear OS's shape that hugs the bottom curve): the owner found
  it used "a weird bottom portion of the screen" (11-19).
- After an action finishes (Log it), go straight back to the watch home and confirm there with
  a short chip (Logged · Undo, 5 s), not a separate screen.
- What the watch home shows is the person's choice (goals, what they need most, or one number),
  set on the phone in Settings › Watch (the watch's Today screen toggle only until the phone picks).
  No custom watch face: a tile and watch-face slots (complications: small circle, long bar, ranged
  value for side slots and the big box, and a Say slot) instead.
- Watch-face shortcuts **do something when tapped**, not just report a number (owner, 11-20).
  Each is its own entry in the face's slot picker so the person chooses (Need most + Say, Usual
  meal now, Again, Close the gap, Since you logged, Waiting on phone, Your main goal, Say); Settings
  › Watch lists them and says how to add one. A one-tap log always lands on home with Undo.
- A screen with more than three things on the round watch is a `ScalingLazyColumn`, like Log
  again, so nothing is cut off by the bottom of the circle.
- The first Say shows a one-time tip; nothing else interrupts.
- Watch debug events carry counts and timings, never the words said.
- Text is at least 21 px on the 480 px screen (about 11 sp) and buttons at least 40 dp tall.
  Swipe right goes back; no Back buttons.

## 9. Before a UI change is done

1. It follows sections 2–8, or this page is updated in the same commit, with the reason.
2. Owner-facing choices go on a Design canvas as full app screens first (see CLAUDE.md).
3. It has been checked in light and dark, and at 200% font size.
4. Any bug fixed on the way has its regression test (CLAUDE.md).
