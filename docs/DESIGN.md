# Beamline design

Status: draft for review, 2026-10-28.

## Goal

Mirror the screen and sound of an iPad, a Pixel 8 and a Galaxy S26+ to a Fire TV
Stick with one free, open-source TV app, with smooth video and sound, good picture
quality and no ads, accounts or tracking. The main use is watching video that plays
better on the phone than on the TV (for example ClipMyHorse, or web apps like Longines
TV), so smooth playback matters more than a low delay.

## Constraints that shape the design

| Fact | Consequence |
|---|---|
| iPads only mirror over AirPlay. | The TV app must speak AirPlay. We reuse UxPlay (GPLv3) instead of writing it from scratch. |
| Pixels only mirror over Google Cast, and Cast senders reject receivers without a Google-issued device certificate. | We cannot be a target for the Pixel's built-in Cast button. Android phones use our own small sender app instead. |
| Fire TV's built-in Miracast mirroring is unreliable. | Samsung phones use the same sender app as the Pixel. |
| Apps can mark windows as secure (FLAG_SECURE) and opt out of audio capture. | DRM video (Netflix, Disney+ and similar) shows black, and some apps are silent. No receiver can change this. |
| The reference stick (Fire TV Stick 4K Max, AFTKA) runs Fire OS 7 (Android 9, API 28) with a 32-bit userland and MediaTek H.264/HEVC hardware decoders. | The TV app targets API 25+ and ships armeabi-v7a as well as arm64-v8a. |

## Components

```
 iPad ──AirPlay──────────────┐
                             ▼
 Pixel 8 ──Beamline protocol──► Beamline TV app (Fire TV / Android TV)
 Galaxy S26+ ─Beamline protocol┘   ├─ AirPlay receiver (UxPlay via JNI)
                                    └─ Beamline receiver (Kotlin)
```

Gradle modules:

| Module | Kind | Purpose |
|---|---|---|
| `protocol` | Kotlin/JVM library | Message types, framing, versioning, pairing code derivation. No Android dependencies, so it is unit-tested on the JVM. |
| `sender` | Android app, minSdk 29 | Runs on the phones. Screen capture, encoding, discovery, pairing, Quick Settings tile. |
| `receiver` | Android TV app, minSdk 25 | Runs on the Fire TV. Discovery advertisement, decoding, playback, TV UI. |
| `airplay` | Android library with native code (added in M4) | UxPlay compiled with the NDK plus JNI bindings, used by `receiver`. |

Package names use the `io.github.lumpilabs.beamline` namespace.

## Beamline protocol, version 1

### Discovery

The TV app registers a DNS-SD service `_beamline._tcp` through `NsdManager`.
TXT record keys:

| Key | Example | Meaning |
|---|---|---|
| `v` | `1` | Highest protocol version the receiver speaks. |
| `id` | `3f9a…` | First 16 hex characters of the SHA-256 of the receiver's certificate. Stable per install. |
| `n` | `Living room TV` | Display name. |
| `c` | `h264,hevc,opus,aac` | Supported codecs. |
| `r` | `2560x1080@60` | Largest stream the receiver wants. |

The sender lists services it finds and also accepts a manually entered IP address
for networks that block multicast.

### Transport

One TCP connection per session, wrapped in TLS, with `TCP_NODELAY`. Control
messages and media share the connection; each frame is prefixed with a header:

```
u8  type        // 0x01 control (CBOR body), 0x02 video, 0x03 audio
u8  flags       // video: bit0 keyframe, bit1 codec config; audio: bit1 codec config
u32 length      // body length in bytes, big-endian
u64 pts_us      // sender monotonic capture time in microseconds (0 for control)
... body
```

TCP keeps v1 simple and reliable on a home LAN. Head-of-line blocking is handled on
the sender side (see Latency). The frame header is transport-independent, so a UDP
transport with retransmission can be added later without changing the codecs or
control messages.

### Control messages

CBOR two-element arrays of the form `["<name>", {...}]`, the body a map with snake_case
field names; unknown fields are ignored and unknown message names are skipped, so newer
peers can add both. Messages: `hello` (versions, device name, capabilities, and a
`sender_id` that stays the same for one install of the sender app),
`welcome` (chosen version, stream limits, and the audio and video codecs the receiver
plays; a receiver that lists no video codecs takes H.264), `stream` (codec, width, height, fps,
rotation, sent before each new video config), `request_keyframe`, `bitrate_hint`,
`ping`/`pong` (round-trip time and clock offset), `stats`, and `bye` (with reason).
Both sides ping once a second. The sender's `time_us` in pings and pongs is on the
same clock as its `pts_us`, so the receiver can work out from the round trips how far
its picture is behind the sender's screen.

### Security and pairing

Each install generates an EC P-256 key pair and a self-signed certificate on first
start. Sessions use TLS 1.2 or newer with mutual certificate authentication.

On the first connection between a phone and a TV, both screens show a 6-digit code:

```
h    = SHA-256("beamline-pairing-v1" || u32(len(r)) || r || u32(len(s)) || s)
code = (first 4 bytes of h as unsigned big-endian integer) mod 1,000,000, zero-padded to 6 digits
```

where `r` and `s` are the DER certificates of the receiver and the sender. Test
vector: `r = 01 02 03`, `s = 04 05 06` gives `939459`. The user confirms on the phone
that the codes match, and both sides remember the other's certificate fingerprint.
Later connections from a known phone start without a prompt. This is the same
numeric-comparison idea Bluetooth pairing uses: a device in the middle would
produce different codes.

The TV can forget paired phones in its settings, and can be set to accept only
paired phones or to ask every time.

## Sender (phone)

- **Capture:** `MediaProjection` into a `VirtualDisplay` whose surface is the encoder's
  input surface, so frames never pass through app memory. Android 14 and newer ask on
  every session whether to share the whole screen or one app; both work.
- **Video encoding:** hardware `MediaCodec`, H.264 by default. HEVC, for a sharper
  picture at the same bitrate, when the user switches it on in the app, the TV lists it
  in `welcome` (it checks for a hardware decoder at its stream limits), and the phone
  has a hardware HEVC encoder for the size; the first HEVC encoder Android lists can be
  a software one, far too slow for the screen, so the phone picks the hardware one by
  name. If the HEVC encoder won't start, a new `stream` switches the TV back to H.264
  for the rest of the session. Realtime priority (`KEY_PRIORITY` 0), `KEY_LATENCY` 1
  where supported, `KEY_REPEAT_PREVIOUS_FRAME_AFTER` so static screens keep a steady
  stream, long keyframe interval with keyframes on demand
  (`PARAMETER_KEY_REQUEST_SYNC_FRAME`). Output is scaled to the receiver's limit,
  2560×1080 at up to 60 fps by default: Full HD height, and wide enough that a 20:9
  phone in landscape keeps full detail when the TV fills its screen. The bitrate is
  about 1/16 bit per pixel per frame, between 2 and 12 Mbit/s (about 9 Mbit/s for a
  landscape phone).
- **Keep-alive:** some encoders (Samsung's) ignore `KEY_REPEAT_PREVIOUS_FRAME_AFTER`
  and send nothing while the screen is still, so the phone sends a `ping` every second.
- **Reconnect:** the TV answers every `ping`, so 5 seconds without anything from it
  means the connection is gone, even when the socket hasn't noticed (a Wi-Fi drop
  often looks like silence, not an error). Capture keeps running and the phone tries
  again every second for up to a minute, dropping frames meanwhile; the app and the
  tile show "Reconnecting…". Once back, it resends `stream`, both codec configs and a
  keyframe, so the picture continues at the next keyframe. Connections are bound to the
  Wi-Fi network: otherwise Android sends them over mobile data while Wi-Fi is down,
  where each attempt hangs until it times out. Without Wi-Fi the phone waits for it
  instead. A TV on the phone's own hotspot is reached without binding.
- **Rotation:** on an orientation change the encoder is reconfigured, and a new
  `stream` message plus codec config are sent before the next frame.
- **Audio:** `AudioPlaybackCaptureConfiguration` (usages media, game and unknown),
  48 kHz stereo PCM, encoded to Opus at 128 kbit/s with 20 ms frames. The phone keeps
  playing its own audio unless the user mutes it.
- **UI:** a receiver list with manual address entry, and a foreground service (type
  `mediaProjection`) with a notification that has a Stop action.
- **Keep awake:** once the phone locks, Android stops sending the screen's picture, so
  while mirroring the service keeps the screen on, dimmed, with a screen wake lock
  (`FLAG_KEEP_SCREEN_ON` would need a Beamline window on screen, but the user is in
  another app). On by default; a switch in the app turns it off, also mid-session.
- **Quick Settings tile:** one tap mirrors to the last TV used (after Android's
  screen-sharing prompt), another tap stops; with no TV used yet it opens the app. On
  Android 13 and newer the app offers to add the tile. A small invisible activity shows
  the prompt, because a tile can't show it itself.

## Receiver (TV)

- **Video:** `MediaCodec` decoding straight to a `SurfaceView`. Compressed frames wait
  in a buffer and go to the decoder 60 ms before they are due, so the decoder never
  runs out of output slots; each decoded frame is released for its exact display time
  (`releaseOutputBuffer` with a timestamp). Display times follow the sound's clock, and
  are smoothed into an even cadence, because a phone composing 25 fps video on a
  120 Hz screen delivers frames alternately 33 and 42 ms apart.
- **Refresh rate:** the receiver detects steady 24, 25 or 50 fps content and asks the
  TV for a matching display mode (50 Hz for 25 and 50 fps) at the same resolution, so
  every frame is shown equally long. It switches after 4 seconds of steady content and
  back to the default after 15 seconds of other content, because each switch blanks the
  TV for a moment.
- **Fill or fit:** landscape pictures fill the screen by default, cropping the phone's
  black side bars; OK on the remote toggles to showing the whole picture. Portrait
  pictures are always shown whole. The TV remembers the choice, and whether the
  statistics are showing, across restarts.
- **Audio:** Opus decoded with `MediaCodec`, played through a normal mixer `AudioTrack`.
  Playback starts once 300 ms is queued, and a chunk is dropped when more than 600 ms
  piles up. A burst can leave the queue near that limit for good (a reconnect while
  the TV sets up its decoder, or a refresh-rate switch pausing the TV's audio output),
  adding delay and leaving no room for Wi-Fi bunching. So when even the queue's lowest
  level over 2 seconds stays 100 ms above the 300 ms cushion, the excess is skipped
  once, in one piece; the same handles clock drift over a long session. Before
  Android 12 a started or flushed
  track only plays once its whole buffer is full, so while filling, the track's usable
  size is shrunk to the 300 ms start cushion and grown back once the mixer takes it. A
  track whose position stops moving for 300 ms (for example after a refresh-rate switch
  resets the TV's audio output) is flushed and restarted. The audio clock is the master
  clock for A/V sync; without sound, video plays 250 ms behind the fastest observed
  network transit.
- **UI:** a leanback launcher banner, an idle screen with the TV's name and pairing
  status, a full-screen player, and settings that work with the D-pad.
- **Statistics:** Menu on the remote (or Up, on remotes without Menu) shows an overlay
  with how long the phone has been mirroring and how often it reconnected, the
  picture's size, shown frame rate and bitrate, the content rate and the TV's refresh
  rate, the delay behind the phone, the Wi-Fi round trip and jitter (the spread in
  how long frames take to arrive, over the last second and the worst so far), late
  and dropped frames, the sound buffer and A/V sync, and the sound's gaps (dropped
  chunks, skipped sound, restarts). Counts carry over a reconnect. The delay runs
  from capture on the phone to the TV handing the frame to its screen, so the TV's
  own picture processing comes on top. The same figures go to the log once a minute.
- **Sessions:** one active stream at a time. A second sender gets a "TV is busy"
  answer, or replaces the current stream if the user allows that in settings. The TV
  ends a session after 10 seconds without data. A phone that comes back sooner (same
  `sender_id`) replaces its old connection right away instead of being turned away as
  busy, and the last picture stays on screen until the new one arrives.
- **Wi-Fi:** while a phone is connected, the TV holds a Wi-Fi lock (low-latency mode
  from Android 10, high-performance mode before), so its Wi-Fi stays out of power
  saving, where the access point holds frames back and delivers them in bunches.
- **AirPlay (M4):** UxPlay's server runs alongside the Beamline receiver and renders
  into the same player screen, so an iPad and an Android phone use the same TV app.

## Delay

Smooth playback comes first: the TV plays about a third of a second behind the phone,
which hides Wi-Fi hiccups of that length without a stutter. Picture and sound stay in
sync with each other; only the phone itself is ahead.

Planned for M3:

- An optional low-latency mode for presenting and games, under 150 ms from phone
  screen to TV screen on 5 GHz Wi-Fi, with a small buffer.
- The sender never queues: if the socket's unsent data exceeds about two frames, it
  skips encoder output until the next keyframe, asks the encoder for one, and lowers
  the bitrate.
- Bitrate adapts between 2 and 20 Mbit/s based on send backlog and receiver `stats`
  (decode queue length, late frames).

## Known limits

- The Pixel's and Samsung's Cast buttons won't list Beamline; start from the
  Beamline app or its Quick Settings tile.
- Secure (DRM) content is black, and apps that opt out of audio capture are silent.
- AirPlay from iPadOS 27 depends on UxPlay; see
  [UxPlay #535](https://github.com/FDH2/UxPlay/issues/535).
- Android 14+ asks for screen-capture consent at the start of every session. That is
  an Android rule, not something Beamline can skip.
- When a phone locks, Android stops sending the screen's picture. The phone app keeps
  the screen on while mirroring, but the power button still locks it. On the iPad,
  AirPlay video keeps playing while locked (M4).

## Testing

- `protocol`: JVM unit tests for framing, CBOR messages and pairing codes.
- Device tests over adb: the Fire TV (10.10.20.150) plus both phones over wireless
  debugging. Scripts install builds, start sessions and pull logs and statistics.
- `tools/test-sender` streams a test pattern, optionally with sound from an Ogg Opus
  file, to the TV from a PC, so the TV app can be checked without a phone. With
  `--hiccup=<second>` it stalls for a second and then sends everything at once, like
  Wi-Fi; `--hiccup=0` gives the burst a reconnecting phone causes.
- Latency: the statistics overlay plus a stopwatch-style test pattern in the sender
  app, filmed with a phone camera for a glass-to-glass check.
- CI: GitHub Actions builds all APKs and runs unit tests on every push.

## Licensing

The whole repository is GPLv3, because the TV app links UxPlay (GPLv3). Third-party
code keeps its own notices. AirPlay, Google Cast, Chromecast, Fire TV and Miracast are
trademarks of their owners; Beamline is not affiliated with them.
