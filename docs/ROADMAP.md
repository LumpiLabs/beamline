# Beamline roadmap

Each milestone ends with something that runs on the real devices: the Fire TV Stick
4K Max (Fire OS 7), a Pixel 8, a Galaxy S26+ and an iPad.

| Milestone | Scope | Done when |
|---|---|---|
| **M0 Foundation** | Android SDK on the dev PC, Gradle project with `protocol`, `sender` and `receiver` modules, CI build, adb installs through Gradle. | Both apps build in CI and install on the Fire TV and a phone. |
| **M1 Video** | Discovery, TCP session, H.264 capture and playback, start and stop, rotation, Quick Settings tile. | Pixel 8 and Galaxy S26+ mirror to the TV at 1080p with under 150 ms latency. |
| **M2 Audio** | Opus capture and playback, A/V sync, automatic reconnect. | Video with in-sync sound plays for 30 minutes without drift or drop-outs. |
| **M3 Robustness** | TLS with numeric-comparison pairing, adaptive bitrate, HEVC, statistics overlay, clear error messages. | Under 100 ms on 5 GHz Wi-Fi; recovers from Wi-Fi hiccups without restarting. |
| **M4 AirPlay** | UxPlay built with the NDK for armeabi-v7a and arm64-v8a, JNI bindings, shared player screen. | The iPad mirrors with sound through the same TV app. |
| **M5 TV polish** | Idle screen, D-pad settings, start on boot, busy handling, German and English text. | Usable end to end with only the TV remote and the phones. |
| **M6 Release** | Signed release APKs on GitHub, user guide, F-Droid metadata, reproducible builds. | Someone else can install it from the README alone. |
