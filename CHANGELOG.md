# Changelog

Notable changes to Beamline. There is no release yet; see the
[roadmap](docs/ROADMAP.md) for what comes next.

## Unreleased

### Phone app

- Mirrors the screen with sound to the Beamline TV app; start and stop in the app or
  from Quick Settings.
- Picks up again by itself after a Wi-Fi drop.
- Keeps the screen on, dimmed, while mirroring, because Android stops sending the
  picture once the phone locks (can be switched off).
- Optional HEVC for a sharper picture when the phone and the TV both handle it in
  hardware.
- Clear messages when the TV can't be reached, is busy, or was closed.

### TV app

- Smooth, evenly paced playback with sound in sync; switches the TV to 50 Hz for 25 fps
  video.
- OK on the remote switches between filling the screen and showing the whole picture.
- Menu shows statistics: frame rate, delay behind the phone, Wi-Fi jitter, dropped
  frames and sound gaps.
- On Fire TV, a phone that has mirrored once can open the app by connecting, also after
  the TV restarts.

### Both

- English and German.
- `tools/test-sender` streams a test pattern with sound from a PC, for testing the TV
  app without a phone.
