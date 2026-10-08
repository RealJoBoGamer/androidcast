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

## Features

- Fullscreen images (JPG/PNG/WebP/BMP) and looping videos (MP4/MKV/WebM…), with crossfades
- Items play in **filename order** (name them `01_intro.mp4`, `02_guest.jpg`, …)
- **Fill (crop) or fit (letterbox)**. Video audio is **muted by default** so it never bleeds into your podcast
- Optional auto‑advance timer, plus a "blank" (black screen) toggle
- Switch backgrounds with:
  - the **Fire TV remote**, or any **Bluetooth clicker / page‑turner / keyboard / media remote**
  - **Bluetooth serial commands** from a phone app or the included laptop script
- **Wi‑Fi setup over Bluetooth** (`WIFI "network" "password"`)
- **Upload over Bluetooth** (`UPLOAD`), or send a link and let the stick download it over Wi‑Fi (`FETCH`)
- Starts automatically when the stick powers on, and keeps the screen awake

## Install

1. Get the APK: download `androidcast-apk` from the latest run in the repo's
   **Actions** tab, or build it yourself with `./gradlew assembleRelease`
   (output: `app/build/outputs/apk/release/app-release.apk`).
2. On the stick: **Settings → My Fire TV → Developer options**. Turn on **ADB debugging**
   and **Apps from unknown sources** (on newer Fire OS, allow it per app). If
   Developer options is hidden, open **Settings → My Fire TV → About** and click the
   device name 7 times.
3. Install it using either option below:
   - **ADB:** `adb connect <stick-ip>` then `adb install app-release.apk`
   - **Downloader app:** install "Downloader" from the Amazon store and enter a URL where you've hosted the APK
4. Open **AndroidCast** from *Your Apps & Channels*.

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
| 1 – 9 | jump to item |
| Play/Pause, `B`, `.` | blank screen on/off |
| Menu / Select / `I` | status panel (Bluetooth name, Wi‑Fi, IP, folder) |
| Up (while the status panel is open) | make the stick discoverable for 5 minutes so a phone can pair |

## Control from a phone or laptop (Bluetooth serial)

The stick runs a Bluetooth **Serial Port Profile** server, so no custom phone app is needed.

**Android phone:** pair with the stick. Either use the stick's Bluetooth settings,
or press Menu then Up in AndroidCast and pair from the phone. Install a serial
terminal such as **"Serial Bluetooth Terminal"** and connect to the stick. Type
commands, or set up its macro buttons as `NEXT`, `PREV`, `GOTO 1`, … to get a
one‑tap remote.

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
| `INTERVAL <seconds>` | auto‑advance (`0` = off, the default) |
| `FIT cover\|contain` | crop to fill (default) or letterbox |
| `AUDIO on\|off` | play video sound (default off) |
| `AUTOSTART on\|off` | open on boot (default on) |
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
- Use **H.264 MP4, 1080p or lower** for videos. Old sticks can't decode 4K or
  HEVC/VP9 reliably. Short loops (10–60 s) keep files small.
  `ffmpeg -i in.mov -c:v libx264 -crf 23 -vf scale=-2:1080 -an out.mp4`
- Animated GIFs show only their first frame, so convert them to MP4.
- Pressing **Home** on the Fire remote leaves the app. Reopen it from Your Apps,
  or reboot the stick and it will start automatically.

## Project layout

```
app/src/main/java/com/androidcast/
  MainActivity.kt            fullscreen player + remote/clicker keys
  BluetoothControlServer.kt  RFCOMM/SPP server
  CommandProcessor.kt        text command protocol (upload, Wi‑Fi, playback…)
  WifiSetup.kt               join Wi‑Fi networks
  MediaLibrary.kt / Prefs.kt storage and settings
  BootReceiver.kt            start on boot
tools/androidcast.py         laptop control & upload script
```
