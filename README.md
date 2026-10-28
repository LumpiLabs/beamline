# Beamline

Free, open-source screen mirroring to Fire TV and Android TV: one TV app for iPads,
iPhones and Macs (over AirPlay) and Android phones (with the Beamline sender app).
No ads, accounts, subscriptions or tracking.

> **Status:** in development, nothing to install yet. The TV app receives and plays
> video (tested on a Fire TV Stick 4K Max); the phone app is written but not yet tested
> on phones. See the [design](docs/DESIGN.md) and [roadmap](docs/ROADMAP.md).

## How it works

- **TV:** install the Beamline TV app on your Fire TV or Android TV.
- **iPad, iPhone, Mac:** open Screen Mirroring and pick the TV. No app needed.
- **Android phones:** install the Beamline sender app and tap the TV in the app or
  in Quick Settings.

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
| `tools/test-sender` | PC tool that streams a test pattern to the TV app, for testing without a phone. |

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

## License

GPLv3. See [LICENSE](LICENSE). The AirPlay receiver is based on
[UxPlay](https://github.com/FDH2/UxPlay) (GPLv3).

AirPlay, Google Cast, Chromecast, Fire TV and Miracast are trademarks of their
respective owners. Beamline is not affiliated with Apple, Google or Amazon.
