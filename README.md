# AndroidCast

Turns an old Amazon Fire TV Stick into a **dedicated podcast backdrop screen**:
fullscreen looping images and videos that you switch with a Bluetooth clicker or
from your phone or laptop over Bluetooth. You can also set up Wi‑Fi and upload new
backgrounds over Bluetooth.

## Do I need a custom Android OS?

**No, and it's usually not worth it.** Fire TV sticks have locked bootloaders.
Community exploits let you unlock a few specific models on old firmware (the 2nd‑gen
"tank" stick and the first Fire TV Stick 4K "mantis", for example). After that you
can flash unofficial ROMs, but it's model‑ and firmware‑specific, can brick the
stick, and gives you an old, unmaintained Android with poor video driver support.

Fire OS is already Android. A sideloaded app can do everything this project needs:
fullscreen playback, Bluetooth input, a Bluetooth serial server, Wi‑Fi setup and
start on boot. That's what AndroidCast is. It runs on every Fire TV Stick from Fire
OS 5 (Android 5.1) upwards.

## Two apps

| App | Install on | What it does |
|---|---|---|
| **AndroidCast** (`display/`) | the Fire TV stick (or an Android phone/tablet to try it out) | fullscreen looping backgrounds |
| **AndroidCast Remote** (`controller/`) | your Android phone | pick the stick from your paired Bluetooth devices, then switch backgrounds, upload files, change settings, set up Wi‑Fi |

## Features

- Fullscreen images (JPG/PNG/WebP/BMP) and videos (MP4/MKV/WebM…), with crossfades. Videos play once and stay on their last frame (or loop, if you turn on `LOOP`)
- **Fast Wi‑Fi transfers:** when the phone and the display are on the same Wi‑Fi, uploads and previews go over the network instead of Bluetooth
- **Shares your phone's Wi‑Fi:** if the display has no Wi‑Fi, the remote app offers to send it your phone's network. The display remembers every network it's been given and joins whichever one is in range
- **Quiet mode:** hides Fire TV notifications while the player is on screen
- Items play in **filename order** (name them `01_intro.mp4`, `02_guest.jpg`, …)
- **Fill (crop) or fit (letterbox)**. Video audio is **muted by default** so it never bleeds into your podcast
- Optional auto‑advance timer, plus a "blank" (black screen) toggle
- Switch backgrounds with:
  - the **Fire TV remote**, or any **Bluetooth clicker / page‑turner / keyboard / media remote**
  - **Bluetooth serial commands** from a phone app or the included laptop script
- **Wi‑Fi setup over Bluetooth** (`WIFI "network" "password"`)
- **Upload over Bluetooth** (`UPLOAD`), or send a link and let the stick download it over Wi‑Fi (`FETCH`)
- Starts automatically when the stick powers on or the TV wakes up, and keeps the screen awake

## Install

Get the APKs: download `androidcast-apks` from the latest run in the repo's
**Actions** tab, or build them with `./gradlew assembleRelease`:

- `display/build/outputs/apk/release/display-release.apk`: goes on the Fire TV stick
- `controller/build/outputs/apk/release/controller-release.apk`: goes on your phone

**Phone (controller):** copy the APK to the phone, open it, and allow installing from
that app when asked. If Play Protect warns you, tap *More details → Install anyway*.

**Fire TV stick (display):** use ADB, as described in the next section.

## Installing and uploading with ADB (from a computer)

ADB sends files to the stick over your Wi‑Fi. It's the fastest way to load big videos.

**1. Turn on ADB on the stick.** Go to **Settings → My Fire TV → Developer options**
and turn on **ADB debugging** and **Apps from unknown sources** (on newer Fire OS,
allow it per app). If Developer options is missing, open **Settings → My Fire TV →
About** and click the device name 7 times. The stick's IP address is under
**About → Network**.

**2. Install adb on your computer.**

```sh
sudo pacman -S android-tools        # Arch
sudo apt install adb                # Debian/Ubuntu
# Windows/macOS: download "SDK Platform-Tools" from developer.android.com
```

**3. Connect.** The computer and the stick must be on the same Wi‑Fi.

```sh
adb connect 192.168.1.50            # use your stick's IP
```

The first time, the TV asks **"Allow USB debugging?"**. Tick *Always allow* and
choose **OK**, then run `adb connect` again. `adb devices` should list the stick
as `device`.

**4. Install the display app.**

```sh
adb install -r display-release.apk
adb shell am start -n com.androidcast/.MainActivity     # open it
```

`-r` lets you install over an older version without losing your backgrounds.

**5. Upload backgrounds.**

```sh
D=/sdcard/Android/data/com.androidcast/files/backgrounds

adb shell mkdir -p $D               # only needed if the app has never been opened
adb push 01_intro.mp4 02_studio.jpg $D/
adb push ~/Pictures/podcast/. $D/   # a whole folder
adb shell ls -l $D                  # see what's there
adb shell rm "$D/02_studio.jpg"     # delete one
```

If AndroidCast is open, new files appear on screen automatically. You don't need to restart it.

**Uninstall:** `adb uninstall com.androidcast`. This also deletes the backgrounds.

> The first install needs Wi‑Fi, since Fire OS setup itself requires a network. After
> that, the Bluetooth Wi‑Fi setup lets you move the stick to a new location or network
> without the TV menus.

## Pairing a controller

**Bluetooth clicker / keyboard / media remote:** go to **Settings → Controllers &
Bluetooth Devices → Other Bluetooth Devices → Add**, and put the clicker in pairing mode.

| Key | Action |
|---|---|
| Right / Down / Page Down / Next / Space | next background |
| Left / Page Up / Previous | previous background |
| Tap the right / left third of the screen | next / previous (phones & tablets) |
| 1 – 9 | jump to item |
| Play/Pause, `B`, `.` | blank screen on/off |
| Menu / Select / `I` | status panel (Bluetooth name, Wi‑Fi, IP, folder) |
| Up (while the status panel is open) | make the stick discoverable for 5 minutes so a phone can pair |

## Start automatically when the stick powers on

AndroidCast opens by itself when the stick boots, and again whenever the TV or
screen wakes up. Do these one‑off steps with adb (see above) to make it reliable:

```sh
# 1. Open the app once after installing. Android won't auto-start an app
#    that has never been opened.
adb shell am start -n com.androidcast/.MainActivity

# 2. Allow it to open itself from the background. Needed on newer Fire OS;
#    harmless on older versions.
adb shell appops set com.androidcast SYSTEM_ALERT_WINDOW allow

# 3. Test it.
adb reboot
```

After the reboot, the Amazon home screen appears and then AndroidCast opens on top
of it. The app keeps checking for 2 minutes after boot, because older sticks are slow
to finish starting.

If it doesn't open, check what happened:

```sh
adb logcat -d -s AndroidCast       # e.g. "received BOOT_COMPLETED", "opening player (boot +20s)"
adb shell dumpsys package com.androidcast | grep -iE "stopped=|RECEIVE_BOOT"
```

No `received BOOT_COMPLETED` line means Fire OS didn't tell the app it had booted.
Usually that's because the app was force‑stopped and hasn't been opened since. Open
it once, then reboot again.

On the stick, also set:
- **Settings → Display & Sounds → Screensaver → Start time: Never**
- **Settings → Display & Sounds → Screensaver → Sleep: Never**, if your Fire OS version has it

Power the stick from **its own wall adapter** if you want it to stay running when the
TV is off. When it's powered from the TV's USB port it reboots each time the TV turns
on, which also works but takes about a minute.

To stop it taking over the screen (for example, to watch Netflix on the stick), send
`AUTOSTART off`. Pressing **Home** always gets you out until the next boot or wake.
You can check your Fire OS's Android version with `adb shell getprop ro.build.version.sdk`
(22 = Fire OS 5, 25 = Fire OS 6, 28 = Fire OS 7, 30 = Fire OS 8).

## Wi‑Fi transfers and sharing Wi‑Fi from your phone

When the remote app connects, it asks the display for its Wi‑Fi address over
Bluetooth and checks it can reach it. The line under the device name tells you which
route it's using:

- **⚡ Same Wi‑Fi as the display:** uploads and previews go over Wi‑Fi (fast). Bluetooth
  is still used for the buttons.
- **…files go over Bluetooth:** they're on different networks, the display has no
  Wi‑Fi, or the router blocks devices from talking to each other (common on guest
  networks).

If the display has no Wi‑Fi and the phone does, the remote offers to send the phone's
network. Android doesn't let apps read saved Wi‑Fi passwords, so you type it once and
the phone remembers it. Next time it's sent automatically. The app asks for location
permission only because Android requires it to see the Wi‑Fi network's name.

The display keeps every network it has been given and joins whichever one is in range
by itself.

Wi‑Fi transfers use port 8642 on the display and need a secret token that's only
handed out over the paired Bluetooth link, so other devices on the network can't
upload or delete anything.

## Using a different launcher (Home button)

Fire OS ignores Android's "default launcher" setting. Home and start‑up always go to
Amazon's home screen, and the 1st‑gen stick can't be rooted to change that.
AndroidCast works around it. When you press Home, it covers the screen in black and
opens your chosen launcher. After Home is pressed, Android holds back other apps for up
to **5 seconds**, so expect a short black screen before your launcher appears.

**One‑off setup:**

```sh
adb shell settings put secure enabled_accessibility_services com.androidcast/com.androidcast.HomeRedirectService
adb shell settings put secure accessibility_enabled 1
```

**Choose what Home opens:**
- In the remote app: **Home button opens…**
- Or with a command: `HOME androidcast` (the default), `HOME off` (Amazon home screen as
  normal), or `HOME <package>` for another launcher you've installed. `APPS` lists the
  installed apps and their package names.

To use another launcher, install its APK with `adb install` (pick one that supports
Android 5.1), then choose it with `HOME`.

**If Home still goes to Amazon:**

```sh
adb shell settings get secure enabled_accessibility_services   # should list com.androidcast/...
adb logcat -c                                                  # clear the log, press Home, wait 10 s, then:
adb logcat -d -s AndroidCastHome
```

**Getting back to Amazon's screens:**
- **Press Home twice quickly** to get Amazon's home screen.
- In AndroidCast, press **Menu, then Down** to open Fire TV Settings.

**To undo it completely:**

```sh
adb shell settings put secure enabled_accessibility_services '""'
adb shell settings put secure accessibility_enabled 0
```

## Hiding Fire TV pop‑ups (quiet mode)

**Notifications:** AndroidCast can dismiss other apps' notifications while the player
is on screen. Fire TV has no settings screen for this, so grant it once with adb:

```sh
adb shell settings put secure enabled_notification_listeners com.androidcast/com.androidcast.NotificationBlocker
```

`STATUS` shows `quiet: on` once it's working (without "no notification access").
Turn it off with `QUIET off` or the switch in the remote app.

**System messages** (such as "remote not detected") aren't notifications. No ordinary
app can block them. To stop the remote message, keep the Fire TV remote paired with
fresh batteries. If one keeps appearing, find out which app shows it while it's on screen:

```sh
adb shell dumpsys window windows | grep -E "mCurrentFocus|mFocusedApp"
```

Tell me the package name it shows. Disabling system packages can break the stick, so
check before trying it.

## Pairing the stick with your phone using adb

The Remote app only lists devices that are already paired. If the stick doesn't
show up, connect adb (see above) and use one of these.

**Option A (most reliable): the stick pairs with your phone.**
Find your phone's Bluetooth address (phone **Settings → About phone → Status**,
"Bluetooth address"). Keep the phone's Bluetooth settings screen open, then run:

```sh
adb shell am start -n com.androidcast/.MainActivity --es pair AA:BB:CC:DD:EE:FF
```

Accept the pairing request on the phone, and on the TV if it asks.

**Option B: make the stick discoverable for 5 minutes**, then pair from the phone's
Bluetooth settings:

```sh
adb shell am start -n com.androidcast/.MainActivity --ez discoverable true
```

The TV shows "allow other devices to see this device?". Choose **Allow** with the remote.

To check what the stick is called and whether it's discoverable:

```sh
adb shell dumpsys bluetooth_manager | grep -iE "name:|address:|ScanMode|state:"
```

`SCAN_MODE_CONNECTABLE_DISCOVERABLE` means it's visible right now.

## Using the AndroidCast Remote app

1. Pair your phone with the stick. On the stick, open AndroidCast and press
   **Menu**, then **Up** to make it visible. On the phone, tap **Pair a new device**
   in AndroidCast Remote (this opens your Bluetooth settings) and pair with it.
2. Go back to AndroidCast Remote. It lists your paired devices with the last one
   you used and TV‑like devices at the top. Tap the stick.
3. Use **Previous / Next**, or tap a preview in the grid of backgrounds to show it (the one on the TV is outlined). Long‑press a preview to delete it.
   Use **Upload** to send pictures and videos from your phone, and the settings for
   fill/fit, sound, auto‑advance, Wi‑Fi and downloading from a link.

AndroidCast must be open on the stick while you use the remote.

## Control from a terminal (Bluetooth serial)

The stick runs a Bluetooth **Serial Port Profile** server, so no custom phone app is needed.

**Any Android phone:** the AndroidCast Remote app above is easiest. A generic
serial terminal app such as "Serial Bluetooth Terminal" also works for typing
commands.

**iPhone:** iOS doesn't allow Bluetooth serial (SPP), so use a Bluetooth clicker
for switching, and a laptop (or `FETCH`) for uploads.

**Laptop (Linux / Windows, Python 3.9+, no packages):** pair, then:

```sh
python tools/androidcast.py AA:BB:CC:DD:EE:FF status
python tools/androidcast.py AA:BB:CC:DD:EE:FF wifi "Studio WiFi" "hunter22"
python tools/androidcast.py AA:BB:CC:DD:EE:FF upload 01_intro.mp4 02_studio.jpg
python tools/androidcast.py AA:BB:CC:DD:EE:FF next
python tools/androidcast.py AA:BB:CC:DD:EE:FF shell      # interactive
```

(The stick's Bluetooth address is shown in your laptop's Bluetooth settings after
pairing. The `status` command shows its name.)

### Commands

One command per line. Put arguments with spaces in `"double quotes"`. Each reply ends
with a line starting `OK` or `ERR`.

| Command | |
|---|---|
| `NEXT` / `PREV` | switch background |
| `GOTO <n>` / `SHOW <file>` | jump to item number or filename |
| `BLANK on\|off` | black screen |
| `LIST` / `STATUS` / `HELP` | info |
| `THUMB <file> [width]` | JPEG preview: replies `DATA <bytes>`, the bytes, then `OK` |
| `INTERVAL <seconds>` | auto‑advance (`0` = off, the default) |
| `FIT cover\|contain` | crop to fill (default) or letterbox |
| `AUDIO on\|off` | play video sound (default off) |
| `AUTOSTART on\|off` | open on boot (default on) |
| `LOOP on\|off` | loop videos (default off: play once, stay on the last frame) |
| `QUIET on\|off` | hide other apps' notifications while showing (default on; needs the adb step below) |
| `LAN` | the display's Wi‑Fi address and token, used by the remote app for Wi‑Fi transfers |
| `HOME androidcast\|off\|<package>` | what the Home button opens (needs the adb step below) |
| `APPS` | list installed apps and their package names |
| `WIFI "<name>" "<password>"` | join a Wi‑Fi network (omit password for open networks) |
| `FORGETWIFI "<name>"` | remove a saved network |
| `UPLOAD <file> <bytes>` | stick replies `READY`, then send exactly `<bytes>` raw bytes |
| `FETCH <url> [file]` | download over Wi‑Fi (much faster for video) |
| `DELETE <file>` / `RENAME <old> <new>` | manage files |

Files live in `/sdcard/Android/data/com.androidcast/files/backgrounds/`, and
`adb push` into that folder works too.

## Tips

- **Bluetooth is slow** (roughly 50–200 KB/s on these sticks). Images upload in
  seconds; a 20 MB video takes a few minutes. For big videos, put them somewhere
  with a direct download link and use `FETCH`, or use `adb push`.
- **Video formats:** this stick plays **H.264 MP4, up to 1080p, about 30 fps**. Phones
  usually record H.265/HEVC, 4K, 60 fps or HDR, and those show "Can't play" with the
  reason. The **AndroidCast Remote** app checks each video and **converts it on the
  phone automatically** before uploading. From a computer, convert with
  `tools/convert-for-tv.sh video.mp4` (needs ffmpeg), then upload the `.tv.mp4` file.
- Use **H.264 MP4, 1080p or lower** for videos. Old sticks can't decode 4K or
  HEVC/VP9 reliably. Short loops (10–60 s) keep files small.
  `ffmpeg -i in.mov -c:v libx264 -crf 23 -vf scale=-2:1080 -an out.mp4`
- Animated GIFs show only their first frame, so convert them to MP4.
- Pressing **Home** on the Fire remote leaves the app. Reopen it from Your Apps,
  or reboot the stick and it will start automatically.

## Project layout

```
display/src/main/java/com/androidcast/
  MainActivity.kt            fullscreen player + remote/clicker keys
  BluetoothControlServer.kt  RFCOMM/SPP server
  CommandProcessor.kt        text command protocol (upload, Wi‑Fi, playback…)
  WifiSetup.kt               join Wi‑Fi networks
  MediaLibrary.kt / Prefs.kt storage and settings
  BootReceiver.kt            start on boot
controller/src/main/java/com/androidcast/controller/
  DevicePickerActivity.kt    choose a paired Bluetooth device
  RemoteActivity.kt          remote control screen (buttons, uploads, settings)
  CastConnection.kt          Bluetooth serial client
tools/androidcast.py         laptop control & upload script
```
