# Home TV setup: Electricity app + Clean Your TV customisations

Everything needed to rebuild the home TVs if something breaks.

| Folder | What it is |
|---|---|
| `app/` | **Electricity** - Android TV app (`com.yashg.electricity`) that reads MGVCL smart-meter usage and estimates the bill |
| `tools/set-login.ps1` | One-time: saves the MGVCL login on the TV (typed on the PC, stored encrypted on the TV) |
| `cleanyourtv/patches/` | Local changes to the [Clean Your TV](https://github.com/Gathin23/cleanyourtv) launcher |
| `cleanyourtv/themes/` | Theme `config.json` per TV (push with `tools/theme.sh`) |
| `cleanyourtv/debloat/` | The package lists disabled on each TV (undo with `tools/restore.sh`) |

## TVs

| TV | Model | Setup |
|---|---|---|
| Main TV | Xiaomi MiTV-AXSO1 (dangalFHD, Android 9) | Debloat, launcher, Apple glass theme, **utility card**, Electricity app, AdGuard Private DNS |
| Second TV | Xiaomi MiTV-AXSO2 (dangalUHD, Android 9) | Debloat, launcher, photo wallpaper + glass theme, starts on Home (`HOME_KEY_SERVICE=1`) |
| Airtel TV | Xiaomi MiTV4I (magnolia, Android 9) | Bare debloat, opens HDMI 3 (Airtel Xstream) at power-on, one-tile Home |

## Electricity app

- Signs in to the MGVCL smart-meter portal in a hidden WebView with the saved login, opens **Usage → Daily**
  and reads the chart (the portal's own data calls need its JWT/CSRF tokens, so it reads the rendered page).
- Refreshes **every 2 hours** in the background (JobScheduler); opening the app shows the last reading at once.
  OK on the remote refreshes, Menu shows the website itself (for a sign-in problem).
- Publishes rows on `content://com.yashg.electricity.utilities/rows` for the launcher's utility card, with a
  `level` (ok / warn / alert → green / amber / red) and an `alert` line:
  - **Slab warning**: amber when the month is on course to pass 100 units, red once past it (a unit goes from
    about ₹6.97 to ₹7.71 all-in); otherwise the card says how many units are left to the next slab.
  - **Spike alert**: the latest day at 2× or more a usual day at home (median of the last 30 days, leaving out
    days under 2 kWh when the house is empty) and at least 3 kWh over it turns the card red.
  - **Time of day**: the share of units in each band (night, peaks) when the portal's chart has it. This meter's
    daily chart currently returns zeros for the bands, so the split stays hidden until the portal fills them.
- Bill estimate (`Bill.java`), MGVCL RGPU monthly, as a real bill is built:
  GERC slabs (₹3.05 first 50, ₹3.50 next 50, ₹4.15 to 250, ₹5.20 above) + ₹70 fixed (load > 6 kW)
  + FPPPA ₹2.72/unit − night discount (₹0.60 × ~27% of units) → + 15% electricity duty.
  **Update `FPPPA_PER_UNIT` when a new bill shows a different fuel adjustment.**

Build and install (JDK 17+, Android SDK 34):

```bash
./gradlew assembleRelease
adb -s <tv ip>:5555 push app/build/outputs/apk/release/app-release.apk /data/local/tmp/elec.apk
adb -s <tv ip>:5555 shell pm install -r /data/local/tmp/elec.apk
```

Save the login once (asks for username and password; nothing is stored on the PC):

```bash
powershell -ExecutionPolicy Bypass -File tools/set-login.ps1 -TvHost <tv ip>
```

The launcher asks once whether to show the Electricity card: answer **Show**.

## Launcher utility card (Clean Your TV patch)

`cleanyourtv/patches/utility-card.patch` adds `UtilityCard.java` and an off-by-default `utility_card` switch:
a glass card above the dock with one tile per app that publishes rows through a provider carrying meta-data
`com.gathin.tvhome.UTILITY_ROWS` (columns: `title`, `accent`, `primary`, `detail`, `updated`). The launcher reads
those rows only; it still has no network. Future home devices add their own provider and appear as more tiles.
Remote: Up from the dock selects the card, OK opens the source app, Down returns to the dock, Up goes to the top bar.

`cleanyourtv/patches/config-xml-dock-and-card.patch` is the local `config.xml`: 6-column dock
(Netflix, Prime Video, YouTube, JioHotstar, Sony LIV, Z5), Live TV = `com.android.tv`, `utility_card` default.

Made against cleanyourtv commit `15b3e5ac` (2026-11-09). To re-apply after pulling a newer cleanyourtv:

```bash
cd cleanyourtv
git apply --3way ../mgvcl-tv/cleanyourtv/patches/utility-card.patch
git apply --3way ../mgvcl-tv/cleanyourtv/patches/config-xml-dock-and-card.patch
cd launcher && TV_HOST=<tv ip> STOCK_LAUNCHER=none ./build-and-install.sh
cd .. && TV_HOST=<tv ip> tools/theme.sh ../mgvcl-tv/cleanyourtv/themes/axso1
```

Themes: copy a `themes/<tv>/config.json` into a folder with its wallpaper (`axso1` includes the glass wallpaper
from the cleanyourtv repo, MIT licence, © Gathin; `axso2` used a family photo that is not stored here) and push it with `tools/theme.sh`.

## Security notes

- The MGVCL password is never in this repo or on the PC: `set-login.ps1` sends it once to the TV, where it is
  encrypted with an Android Keystore key. The receiver only accepts the ADB shell (`android.permission.DUMP`).
- Close network ADB on the TVs after any change (USB debugging off, revoke authorisations, reboot,
  `tools/adb-closed.py <tv ip>`): port 5555 is plaintext.

## Gestures (test build)

`gestures/` is a test app for controlling the TV with hand gestures through a USB webcam (the MiTV-AXSO1 exposes
it as a standard camera). It uses Google's MediaPipe gesture recogniser (`gesture_recognizer.task`, Apache 2.0)
on the TV's CPU, about 4 recognitions a second on this 32-bit chip, and shows the camera, the hand points and the
recognised gesture. Frames stay in memory only; the app has no internet permission.

| Gesture | Meaning |
|---|---|
| Open palm tilted to your right / left, held | Scroll right / left (repeats while held) |
| Upright open palm | Rest (does nothing) |
| Thumbs up (or point up) / thumbs down, held | Up / down |
| Fist next to your face | Play / pause |
| Pinch (OK sign) | OK / open |
| Victory | Back |
| "I love you" sign | Home |

Tuned with a guided test from the sofa (press OK in the app; it logs to
`/sdcard/Android/data/com.yashg.gestures/files/diag.txt`). The webcam follows the hand with a full-resolution window
so a hand ~3 m away is big enough for the model. Flicks were dropped: at ~5 frames a second a wrist snap is one or two
frames and its direction can't be told from the pull before it; the held tilt reads cleanly. Last run: right 9/9,
left 7/7, up 29 frames, down 37 frames, no scrolls with the hand resting.

Build: `./gradlew :gestures:assembleRelease`, install, then `adb shell pm grant com.yashg.gestures android.permission.CAMERA`.
