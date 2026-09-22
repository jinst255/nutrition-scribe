# Play Store readiness

What's done, and what genuinely still blocks a listing. Nothing here is guesswork
about the build — the ✅ items were verified on 2026-09-19.

## Done

- ✅ **Release signing.** Both modules sign with `~/keystores/protein-scribe.jks`
  via `keystore.properties` (gitignored). Verified with `apksigner verify`:
  `CN=Protein Scribe, OU=Personal, L=Rexburg, ST=Idaho, C=US`.
- ✅ **Phone and watch share an applicationId** (`com.proteinscribe`). They did
  not before — the watch module was `com.proteinscribe.wear`, which would have
  made them two unrelated apps that Play could never pair. Changed 2026-09-19.
- ✅ **Installs and runs from the signed release APK** on an Android 14 device
  (A34 emulator), full onboarding → log → history → export path exercised.
- ✅ **Database migration** from schema v1 to v2 is hand-written, not
  destructive. An existing user's food log survives the update.

## Blocks a listing — needs you, not code

1. **Google Play Console account** — $25 one-time, needs your identity verified.
   Verification has been taking days to weeks lately, so start it first.
2. **Privacy policy at a public URL.** Required because the app can send what you
   eat to a third party (OpenAI). A draft is below; it needs hosting anywhere
   public — a GitHub Pages page or a Google Doc set to "anyone with the link" is
   accepted.
3. **Data safety form.** Declare: food/health data, stored on device; text sent
   to OpenAI only when the user supplies an API key; no data collected by you;
   no ads; no analytics.
4. **Store listing assets** — none of these exist yet:
   - 512×512 PNG app icon (the current launcher is a plain vector placeholder)
   - 1024×500 feature graphic
   - 2–8 phone screenshots
   - short description (80 chars) and full description (4000)
5. **Target API level — this is the big one, and the deadline has already
   passed.** Since 31 August 2026, new apps and updates must target **Android 16
   (API 36)**. This project targets **34**. That is a hard rejection at upload,
   not a warning.
   - The **phone** module must go to `compileSdk`/`targetSdk` **36**.
   - The **watch** module is fine: Wear OS only requires API 34+, which it
     already targets.
   - Bumping two API levels means real work, not a one-line change: Android 15
     enforces edge-to-edge layouts and 16KB page alignment, and Android 16 makes
     edge-to-edge non-optional. Budget a session for it and retest on device.
   - An extension to 1 November 2026 can be requested if needed.

## Should fix before launch, not blocking

- **`isMinifyEnabled = false`.** Turning R8 on shrinks the APK a lot (the wear
  APK is 45 MB unminified) but needs keep rules for Room, Retrofit/Moshi
  reflection and kotlinx-serialization. Doing it untested trades size for
  release-only crashes, so it's deliberately off. Worth a session with a tested
  rule set.
- **Build an AAB, not an APK.** Play requires `.aab` for upload
  (`./gradlew :app:bundleRelease`). The APK is only for sideloading.
- **`allowBackup="false"`** means users lose their log on phone transfer. Fine
  for a personal app, worth reconsidering for strangers.
- The **debug log screen** is reachable from Settings in release builds.

## Privacy policy draft

> **Protein Scribe — Privacy Policy**
> Last updated: 19 September 2026
>
> Protein Scribe stores everything you log — the food, the amounts, and the times
> — in a database on your own phone. There is no account, no server of ours, and
> no cloud backup. We cannot see your data, and we do not collect, sell, or share
> any of it.
>
> **If you choose to add an API key**, then each time you log something by voice
> or text, that text (for example "two eggs and toast") is sent to that provider
> — OpenAI or Anthropic, whichever you configured — so it can estimate the
> nutrition. Nothing else is sent: not your name, not your history, not your
> totals. That provider's own privacy policy governs what they do with it. If you
> do not add a key, the app makes no network requests at all.
>
> **Deleting your data**: uninstalling the app deletes everything. You can also
> export a copy at any time from Settings → Export and import.
>
> **Children**: this app is not directed at children under 13.
>
> Questions: [YOUR CONTACT EMAIL]
