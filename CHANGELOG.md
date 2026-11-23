# Changelog

Notable changes to Beamline. There is no release yet; see the
[roadmap](docs/ROADMAP.md) for what comes next.

## Unreleased

### Phone app

- Mirrors the screen with sound to the Beamline TV app; start and stop in the app or
  from Quick Settings.
- Picks up again by itself after a Wi-Fi drop, also with a VPN such as Tailscale on.
- On a weak Wi-Fi the picture gets softer instead of falling behind: the phone lowers
  the bitrate as soon as frames wait to be sent, skips to a fresh keyframe when they
  wait a quarter of a second, and raises it again once the Wi-Fi keeps up.
- Keeps the screen on, dimmed, while mirroring, because Android stops sending the
  picture once the phone locks (can be switched off).
- Optional HEVC for a sharper picture when the phone and the TV both handle it in
  hardware.
- Optional low latency for presenting and games: the TV shows the picture about a
  tenth of a second behind the phone instead of over half a second, with sound
  trailing a little.
- Clear messages when the TV can't be reached, is busy, was closed, or another phone
  took over.
- The notification says where mirroring stands. The first time with a TV it pops up
  with the pairing question and both answers, instead of already saying it mirrors.

### Windows app

- Mirrors a whole screen with the computer's sound to the Beamline TV app, encoded by
  the graphics card (NVIDIA, AMD or Intel), or by Windows itself without one.
- The same pairing, encryption, reconnecting, weak Wi-Fi handling and low-latency mode
  as the phone app.
- Finds the TVs on the network by itself; an address can be typed in too.
- Keeps mirroring while the computer is locked, showing the last picture, and goes on
  once it is unlocked.
- Keeps the computer's Wi-Fi out of power saving while mirroring.

### TV app

- Smooth, evenly paced playback with sound in sync; switches the TV to 50 Hz for 25 fps
  video. Frames go to the decoder as early as it needs, measured all the time, and after
  a stall the picture catches up with the sound within seconds.
- The Fire TV's decoder no longer holds each picture back until the next one arrives,
  so a still screen shows its last change at once, and decoding takes 5 to 10 ms
  instead of 40 to 85.
- OK on the remote switches between filling the screen and showing the whole picture.
- Menu shows statistics: frame rate, delay behind the phone, Wi-Fi jitter, late and
  dropped frames, decode time and sound gaps.
- Settings, opened with OK while no phone is connected: fill the screen, statistics,
  letting another phone take over instead of hearing that the TV is busy, and the
  paired phones with when each last connected, each of which can be forgotten.
- On Fire TV, a phone that has mirrored once can open the app by connecting, also after
  the TV restarts.

### Both

- Encrypted connections (TLS 1.2 or newer). The first time a phone and a TV meet, both
  show a 6-digit code: confirm it on the phone and press OK on the TV, in either order;
  after OK, the TV says when the phone still has to confirm. With the app closed, a
  Fire TV only opens for paired phones.
- English and German.
- `tools/test-sender` streams a test pattern with sound from a PC, for testing the TV
  app without a phone.
