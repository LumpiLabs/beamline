# Beamline

[![Build](https://github.com/LumpiLabs/beamline/actions/workflows/build.yml/badge.svg)](https://github.com/LumpiLabs/beamline/actions/workflows/build.yml)
[![License: GPL v3](https://img.shields.io/badge/license-GPLv3-blue.svg)](LICENSE)

Free, open-source screen mirroring to Fire TV and Android TV: one TV app for iPads,
iPhones and Macs (over AirPlay), Android phones (with the Beamline sender app) and
Windows computers (with Beamline for Windows). No ads, accounts, subscriptions or
tracking. In English and German.

> **Status:** in development, nothing to install yet. A Galaxy S26+ mirrors smoothly
> with sound to a Fire TV Stick 4K Max, gets softer rather than falling behind on a weak
> Wi-Fi, and picks up again by itself after a Wi-Fi drop;
> a Windows laptop mirrors its screen with sound too; the Pixel 8 and the iPad (AirPlay) are still to come. See the [design](docs/DESIGN.md) and [roadmap](docs/ROADMAP.md).

## How it works

- **TV:** install the Beamline TV app on your Fire TV or Android TV and open it. On a
  Fire TV, a paired phone can later start mirroring with the TV app closed: the TV
  wakes up and opens it by itself. Newer Android TVs need the app open.
- **The first time** a phone and a TV meet, both show the same 6-digit code: confirm it
  on the phone and press OK on the TV remote. After that they know each other, and
  the connection is always encrypted.
- **iPad, iPhone, Mac:** open Screen Mirroring and pick the TV. No app needed.
- **Android phones:** install the Beamline sender app and tap the TV in the app or
  in Quick Settings. While mirroring, the phone's screen stays on, dimmed, because
  Android stops sending the picture once the phone locks; a switch in the app turns
  that off. For presenting and games, another switch shows the picture with much less
  delay, at the cost of some smoothness, with the sound trailing a little.
- **Windows computers:** open Beamline for Windows, pick the TV and the screen, and
  click Start mirroring. It sends the whole screen with the computer's sound, encoded by the
  graphics card; to show a browser tab, put the browser in full screen (F11).

On the TV, OK switches between filling the screen and showing the whole picture, and
Menu shows how mirroring is going: frame rate, delay, Wi-Fi jitter, dropped frames and
sound gaps. With no phone connected, OK opens the settings, where you can also forget
a paired phone.

Copy-protected video (Netflix, Disney+ and similar) shows black when mirrored, as it
does with every mirroring app. The Android phone's built-in Cast button can't target
Beamline, because Google only allows certified devices there.

## Building

You need JDK 21 and the Android SDK (platform 37, build tools 37.0.0). Put the SDK
path in `local.properties` (`sdk.dir=...`) or set `ANDROID_HOME`.

```bash
./gradlew build
```

The debug APKs land in `sender/build/outputs/apk/debug/` and
`receiver/build/outputs/apk/debug/`. To install on a device connected over adb, name
it with `ANDROID_SERIAL`, for example the TV:

```bash
ANDROID_SERIAL=192.168.1.50:5555 ./gradlew :receiver:installDebug
```

| Module | What it is |
|---|---|
| `protocol` | Wire format shared by both apps (frames, control messages, pairing codes). |
| `sender` | Android phone app, Android 10 and newer. |
| `receiver` | Fire TV / Android TV app, Android 7.1 (Fire OS 6) and newer. |
| `windows-sender` | Windows app, Windows 10 and 11 (64-bit). |
| `tools/test-sender` | PC tool that streams a test pattern to the TV app, for testing without a phone. |

The Windows app captures through a small C++ library, built with MinGW-w64: its `g++`
on Windows (for example from [WinLibs](https://winlibs.com/)), or
`x86_64-w64-mingw32-g++` on Linux (`apt install g++-mingw-w64-x86-64`). Without one,
the build leaves the library out and the app can't capture. To try it, build it and
start it; it needs Java 21 or newer for now:

```bash
./gradlew :windows-sender:installDist
windows-sender/build/install/beamline/bin/beamline.bat
```

`beamline.bat --tv=192.168.1.50` mirrors from the console instead of the window, and
`--record=test.h264` encodes the screen into a file, for checking capture without a TV.

To check a TV without a phone, open Beamline on the TV and run:

```bash
./gradlew :test-sender:run --args="192.168.1.50"
```

The arguments are `<host> [port] [seconds] [fps] [sound.ogg]`. To test sound too, make a quiet tone with ffmpeg and pass it as the fifth argument:

```bash
ffmpeg -f lavfi -i sine=frequency=440:duration=5 -af volume=0.1 -ac 2 -c:a libopus tone.ogg
```

```bash
./gradlew :test-sender:run --args="192.168.1.50 47800 30 25 $PWD/tone.ogg"
```

Add `--hiccup=10` to hold everything back for one second at second 10 and then send it at once, like Wi-Fi that stalls for a moment. Add `--sender=2` to connect as a second phone, for trying what the TV does when it's busy.

The first run pairs with the TV: the test sender prints the code and takes it as
matching, and the TV asks you to press OK on the remote. It keeps its key in
`~/.beamline/test-sender`, so later runs start straight away.

## Contributing

Bug reports, ideas, translations and code are welcome: see
[CONTRIBUTING.md](CONTRIBUTING.md) and the [code of conduct](CODE_OF_CONDUCT.md).
Report security problems privately, as described in [SECURITY.md](SECURITY.md); it
also lists what isn't protected yet. Beamline collects nothing about you, see
[PRIVACY.md](PRIVACY.md). Changes are listed in the [changelog](CHANGELOG.md).

## License

GPLv3. See [LICENSE](LICENSE). The AirPlay receiver is based on
[UxPlay](https://github.com/FDH2/UxPlay) (GPLv3).

AirPlay, Google Cast, Chromecast, Fire TV and Miracast are trademarks of their
respective owners. Beamline is not affiliated with Apple, Google or Amazon.
