# Installing Nutrition Scribe

Send whoever is installing it the file **`nutrition-scribe.apk`** — by email, Google
Drive, Signal, whatever. Then send them everything below the line.

---

## Nutrition Scribe — how to install it

This app isn't on the Play Store, so your phone will ask you once whether you
trust it. That's normal for an app installed directly rather than from a store.
It takes about a minute.

**1. Download the file.** Tap the `nutrition-scribe.apk` file that was sent to you.
It'll land in your Downloads.

**2. Tap it.** Your phone will say something like *"For your security, your phone
isn't allowed to install unknown apps from this source."* Tap **Settings** on that
message.

**3. Turn on the switch** that says **Allow from this source**, then press back.

**4. Tap Install.** If you get a warning about an unrecognised app, choose
**Install anyway** — that warning just means the app didn't come from the Play
Store.

**5. Open it.**

That's it. You can turn the "allow from this source" switch back off afterwards
if you like; the app will keep working.

### First time you open it

It asks your name, what to track and, if you like, your body details for
targets. Then it offers a place to paste an AI key. **You can skip that.**

- **Without a key** the app runs in offline mode: say, type or scan what you
  ate and it's looked up in the food list and label database built into the app.
  Everything else works the same: history, charts, recipes, exporting.
- **With a key** (OpenAI or Anthropic, a paid account you set up yourself),
  unusual foods, restaurant meals and long sentences are worked out more
  accurately, and you can use Ask to plan meals or ask about your log.

The app remembers what you log, so after a few days your usual foods log in one
go without asking. You can add a key any time in Settings › AI.

### Things worth knowing

- **Your food log lives only on your phone.** There's no account and no cloud
  backup. If you lose the phone, the log goes with it — use Settings → Export and
  import now and then to save a copy.
- **Almost nothing is sent anywhere** without an API key: a barcode the app
  doesn't know is looked up on Open Food Facts (just the number). With a key,
  what you say or type to log goes to OpenAI or Anthropic to be looked up, and
  Ask sends your question with today's log. Settings › AI › Run in offline mode
  stops all of that.
- **Tap a point on the History chart** to jump the list to that day.
- **The little + next to a date** adds an entry to that day — useful when you
  forget to log until the next morning.
