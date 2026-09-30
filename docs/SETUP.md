# Setting up a machine to work on Nutrition Scribe

Linux, x86-64. Everything installs in your home directory, so this works on an
immutable distro too. The paths below match the original machine. Keep them, and the
only files that name a path (`local.properties`, `keystore.properties`) work unchanged.

## 1. Basics

You need `git`, `python3`, `unzip` and `curl`.

For the emulator you also need KVM. `ls -l /dev/kvm` should exist and be readable and
writable by you. If it isn't, add yourself to the `kvm` group and log in again.

## 2. JDK 17 and the Android SDK

```bash
mkdir -p ~/android-tools && cd ~/android-tools

# Temurin JDK 17 (any 17.x works; the original machine had 17.0.20.1+1)
curl -L -o jdk17.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
tar xzf jdk17.tar.gz          # creates jdk-17.x.y+z/

# Android command-line tools: get the current "Command line tools only" Linux zip from
# https://developer.android.com/studio#command-line-tools-only, then:
mkdir -p android-sdk/cmdline-tools
unzip commandlinetools-linux-*_latest.zip -d android-sdk/cmdline-tools
mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest
```

Add this to `~/.bashrc`, fixing the JDK folder name to match what was extracted:

```bash
export JAVA_HOME="$HOME/android-tools/jdk-17.0.20.1+1"
export ANDROID_HOME="$HOME/android-tools/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

Then install the SDK packages:

```bash
sdkmanager --licenses
sdkmanager "platform-tools" "emulator" \
  "platforms;android-36" "platforms;android-34" \
  "build-tools;36.0.0" "build-tools;35.0.0" "build-tools;34.0.0" \
  "system-images;android-34;google_apis;x86_64" \
  "system-images;android-34;android-wear;x86_64"
```

## 3. The repo

```bash
mkdir -p ~/Desktop/Programming && cd ~/Desktop/Programming
git clone https://github.com/jinst255/nutrition-scribe.git
cd nutrition-scribe
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

The debug build and the JVM tests already work at this point, with no private files:

```bash
./gradlew -Dorg.gradle.jvmargs=-Xmx2g :shared:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

## 4. The private archive

Some things must never be on GitHub: the signing key, the label pack, your real food
log, Claude's memory, and this project's Claude conversation. They travel as one file,
`nutrition-scribe-private.tar.gpg`.

- **How it travels:** carry it on a USB stick, or `scp` it across your home network.
  Never use GitHub, a cloud drive or a chat app.
- **How it's locked:** it's encrypted with a passphrase you choose. Don't send the passphrase alongside the file.

```bash
gpg -d nutrition-scribe-private.tar.gpg > nutrition-scribe-private.tar   # asks for the passphrase
mkdir -p /tmp/nsp && tar xf nutrition-scribe-private.tar -C /tmp/nsp
bash /tmp/nsp/unpack.sh ~/Desktop/Programming/nutrition-scribe
rm -rf /tmp/nsp nutrition-scribe-private.tar
```

`unpack.sh` puts each file in its place. It never overwrites anything already there, and
the archive's own `README.txt` lists where each file goes.

If your home folder differs from the old machine's, `unpack.sh` tells you to fix the `storeFile=` path in `keystore.properties`.

Check that the release build is signed with the real key:

```bash
./gradlew -Dorg.gradle.jvmargs=-Xmx2g :app:assembleRelease :wear:assembleRelease
"$ANDROID_HOME"/build-tools/36.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk | grep SHA-256
# must print 989f0462a197bb10b052e941485743837888b94f95384050e0d5725ccedace2f
```

An APK signed with any other key can't be installed over the one already on phones.

## 5. Emulators

```bash
echo no | avdmanager create avd -n A34 -k "system-images;android-34;google_apis;x86_64" -d pixel_6
cat >> ~/.android/avd/A34.avd/config.ini <<'EOF'
hw.lcd.width=1080
hw.lcd.height=2340
hw.lcd.density=450
hw.ramSize=3072
EOF
echo no | avdmanager create avd -n Wear34 -k "system-images;android-34;android-wear;x86_64" -d wearos_small_round

emulator -avd A34 -memory 2048 &     # never while a Gradle build is running
```

## 6. Your phone

1. Turn on Developer options, then USB debugging.
2. Plug the phone in and run `adb devices`.
   - If it shows `no permissions`, your distro needs Android udev rules (for example the
     `android-udev` package) and a replug.
3. Install over the existing app, which keeps its data:
   `adb install -r app/build/outputs/apk/release/app-release.apk`.

## 7. Claude Code

1. Install Claude Code (`curl -fsSL https://claude.ai/install.sh | bash`) and log in.
   - Log in fresh.
   - Never copy `~/.claude/.credentials.json` from another machine.
2. The archive's memory notes and conversation are already in place from step 4.
3. From the repo folder, continue the conversation with `claude --resume <session-id>`.
   `README.txt` in the archive gives the id. Or start a new session: it reads `CLAUDE.md`
   and the memory automatically.
4. Run `/auto-mode-setup` again, because the old machine's auto-mode notes describe the old folder.
5. Put a fresh OpenAI key in `~/.config/nutrition-scribe/openai-key` (`chmod 600`) if you want
   the AI tools. The old key has expired.

## 8. Rebuilding the data (optional)

The shipped `food.db` is committed, and the archive carries the label pack, so you
normally need none of this. The sources are USDA FoodData Central, which is public
domain: <https://fdc.nal.usda.gov/download-datasets.html>.

**Offline food list (`app/src/main/assets/food.db`):** uses SR Legacy (2018-04) and FNDDS Survey (2022-10-28).

```bash
python3 tools/build_food_db.py <dir-with-unzipped-csvs> -o food.db
python3 tools/curate_food_db.py food.db
cp food.db app/src/main/assets/food.db   # then bump assetVersion in FoodDatabase.kt
```

**Label pack (`app/src/main/assets-pack/`):** uses Branded Foods, the 2026-04-30 release (428 MB zip, 2.7 GB unzipped).

```bash
B=~/.cache/fdc-bench
python3 tools/build_branded_db.py $B/FoodData_Central_branded_food_csv_2026-04-30 -o $B/branded_full.db
python3 tools/brandsearch.py index
python3 tools/build_label_pack.py -o labels.db --since 2020-01-01
python3 tools/label_pack_micros.py labels.db --csv $B/FoodData_Central_branded_food_csv_2026-04-30/food_nutrient.csv
gzip -9 -k labels.db
mkdir -p app/src/main/assets-pack && cp labels.db.gz app/src/main/assets-pack/
echo "4|2020-01-01|<product count>" > app/src/main/assets-pack/labels.id
```

The product count is in the pack's `meta` table (`products`). The app only replaces a
pack on a phone when the new one has more products.
