# Beta testing

How a beta round runs: who gets what, what testers are asked to do, and how what
they think comes back. Testers' names and logs never go in this repo.

## Two groups

| Group | Setup | What it tells us |
|---|---|---|
| **With an API key** | Type a restricted OpenAI key at setup (never baked into the APK) | How much AI lookup and cloud transcription help |
| **Without a key** | Skip the key | Whether the built-in food list and label pack are enough on their own |

The debug export records `has_openai_key`, so each log says which group it came from.

Make a separate, restricted key for testers, with a low monthly spending cap.
Cloud transcription costs about $0.003 a minute (`gpt-4o-mini-transcribe`), and a
heavy logger talks for about 1–2 minutes a day: around $1–2 a year per person.

## Handing out a build

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Put the label pack in `app/src/main/assets-pack/` and build a signed release
   (see CLAUDE.md). Always the same keystore, or updates won't install.
3. Send the APK with the message below. On the phone: allow installs from the
   messaging or files app, open the APK, then install.

### Message to testers

> Thanks for trying Nutrition Scribe! It's a food log you talk to: hold the Say
> button and tell it what you ate.
>
> - Use it for a week, the way you normally would.
> - When something looks wrong, press and hold your finger on it for a second
>   or two, then tap what it was. A word or two is plenty. (For the app as a
>   whole: Settings → Beta testing → "Something's wrong? Tell us".)
> - Tapping an entry on Home lets you fix it with − / +, or type or say the change.
> - After a week the app asks three quick questions and to send your log. Please do.
>   It only sends when you tap Send.

## What comes back

Settings opens with a Beta testing section in test builds: the day of the test
week, "Something's wrong? Tell us", "I have an idea", "What I've sent" (each note,
and whether a sent log has carried it yet) and "Send my log now".

On day 7 (the reminder on Home, or the button in that section) the check-in asks
what bothered them most and whether they would keep using it and why. Only a
"Yes" also asks whether they would pay anything, since the app is meant to be free.
Their answers go into the debug log as `feedback` and `beta_checkin` events, and
the log is shared from the same screen. A file arrives as
`nutrition-scribe-debug-<name>-<date>.zip`.

Also in each log:
- `spot_feedback`: press-and-hold reports (debug mode), with the screen, the spot,
  and a screenshot with the spot circled (saved beside the scan photos).
- `user_report`: "Something's wrong with this entry" notes, with the entry as it was stored.
- `flow_start` / `flow_end`: how long each log took, step by step.
- `idea_dismissed`, `ideas_open`: whether the Ideas screen is useful or ignored.

## Questions the beta answers

1. Would they use it after the beta, and why or why not?
2. Would Android users pay anything at all, and how much?
3. Is the API key a clear win? Compare the two groups on how long logging took,
   how often entries were fixed, and what testers said.
