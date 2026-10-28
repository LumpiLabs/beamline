# Beamline roadmap

Each milestone ends with something that runs on the real devices: the Fire TV Stick
4K Max (Fire OS 7), a Pixel 8, a Galaxy S26+ and an iPad.

| Milestone | Scope | Done when | Status |
|---|---|---|---|
| **M0 Foundation** | Android SDK on the dev PC, Gradle project with `protocol`, `sender` and `receiver` modules, CI build, adb installs through Gradle. | Both apps build in CI and install on the Fire TV and a phone. | Done locally; CI runs once the repository is pushed. |
| **M1 Video** | Discovery, TCP session, H.264 capture and playback, start and stop, rotation, Quick Settings tile, fill or fit the screen, matching the TV's refresh rate to the video (50 Hz for 25 fps). | Pixel 8 and Galaxy S26+ mirror smoothly to the TV at up to 1080p. | Done on the Galaxy S26+; Pixel 8 not tested yet. |
| **M2 Audio** | Opus capture and playback, A/V sync, automatic reconnect. | Video with in-sync sound plays for 30 minutes without drift or drop-outs. | Sound, sync and automatic reconnect work on the Galaxy S26+; the 30-minute test is open. |
| **M3 Robustness** | TLS with numeric-comparison pairing, adaptive bitrate, HEVC, statistics overlay, clear error messages, an optional low-latency mode for presenting and games. | Recovers from Wi-Fi hiccups without restarting; low-latency mode stays under 150 ms on 5 GHz Wi-Fi. | Statistics overlay and HEVC (a switch in the phone app) written, not yet tried on the devices. |
| **M4 AirPlay** | UxPlay built with the NDK for armeabi-v7a and arm64-v8a, JNI bindings, shared player screen. AirPlay video too: the AirPlay button in a video app sends the video itself instead of the screen, so it keeps playing while the iPad is locked. | The iPad mirrors with sound through the same TV app, and a video sent with the AirPlay button keeps playing with the iPad locked. | Planned |
| **M5 Polish** | TV: idle screen, D-pad settings, start on boot, busy handling, German and English text. Phone: an option to keep the phone awake (screen dimmed) while mirroring, because Android stops sending the picture when the phone locks. | Usable end to end with only the TV remote and the phones. | Written but not yet tried on the devices: German text in both apps, the phone keep-awake, and on Fire OS the TV ready in the background and after a restart, opened by a known phone. |
| **M6 Release** | Signed release APKs on GitHub, user guide, F-Droid metadata, reproducible builds. | Someone else can install it from the README alone. | Store texts in English and German (`*/fastlane`); screenshots and the rest planned. |
| **M7 Laptops** | Macs already work through AirPlay (M4). A small Windows sender: screen or window capture with sound, hardware H.264, the same protocol and pairing as the phone app. | A Windows laptop mirrors a browser tab with sound to the TV. | Planned |
