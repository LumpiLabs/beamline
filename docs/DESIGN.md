# Beamline design

Status: draft for review, 2026-10-28.

## Goal

Mirror the screen and sound of an iPad, a Pixel 8 and a Galaxy S26+ to a Fire TV
Stick with one free, open-source TV app, with low latency, good picture quality and
no ads, accounts or tracking.

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
| `r` | `1920x1080@60` | Largest stream the receiver wants. |

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

CBOR maps of the form `{"type": "<name>", "value": {...}}`, with snake_case field
names; unknown fields are ignored. Messages: `hello` (versions, device name, capabilities),
`welcome` (chosen version and stream limits), `stream` (codec, width, height, fps,
rotation, sent before each new video config), `request_keyframe`, `bitrate_hint`,
`ping`/`pong` (round-trip time and clock offset), `stats`, and `bye` (with reason).

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
- **Video encoding:** hardware `MediaCodec`, H.264 High profile by default and HEVC
  when both sides support it. Realtime priority (`KEY_PRIORITY` 0), `KEY_LATENCY` 1
  where supported, `KEY_REPEAT_PREVIOUS_FRAME_AFTER` so static screens keep a steady
  stream, long keyframe interval with keyframes on demand
  (`PARAMETER_KEY_REQUEST_SYNC_FRAME`). Output is scaled to the receiver's limit,
  1920×1080 at up to 60 fps by default.
- **Rotation:** on an orientation change the encoder is reconfigured, and a new
  `stream` message plus codec config are sent before the next frame.
- **Audio:** `AudioPlaybackCaptureConfiguration` (usages media, game and unknown),
  48 kHz stereo PCM, encoded to Opus at 128 kbit/s with 10 ms frames. The phone keeps
  playing its own audio unless the user mutes it.
- **UI:** a receiver list, a Quick Settings tile for starting and stopping, and a
  foreground service (type `mediaProjection`) with a notification that has a Stop
  action.

## Receiver (TV)

- **Video:** `MediaCodec` decoding straight to a `SurfaceView`. Frames are released
  as soon as they are decoded unless they are already late relative to audio, in
  which case they are dropped.
- **Audio:** Opus decoded with `MediaCodec`, played through `AudioTrack` with a small
  adaptive jitter buffer (starting at 40 ms). The audio clock is the master clock for
  A/V sync.
- **UI:** a leanback launcher banner, an idle screen with the TV's name and pairing
  status, a full-screen player, a statistics overlay (bitrate, fps, latency, dropped
  frames) on a remote button, and settings that work with the D-pad.
- **Sessions:** one active stream at a time. A second sender gets a "TV is busy"
  answer, or replaces the current stream if the user allows that in settings.
- **AirPlay (M4):** UxPlay's server runs alongside the Beamline receiver and renders
  into the same player screen, so an iPad and an Android phone use the same TV app.

## Latency

Target: under 100 ms from phone screen to TV screen on 5 GHz Wi-Fi at 1080p60.

- The sender never queues: if the socket's unsent data exceeds about two frames, it
  skips encoder output until the next keyframe, asks the encoder for one, and lowers
  the bitrate.
- Bitrate adapts between 2 and 20 Mbit/s based on send backlog and receiver `stats`
  (decode queue length, late frames).
- `ping`/`pong` every second estimates round-trip time and the clock offset, so the
  statistics overlay can show real end-to-end latency.

## Known limits

- The Pixel's and Samsung's Cast buttons won't list Beamline; start from the
  Beamline app or its Quick Settings tile.
- Secure (DRM) content is black, and apps that opt out of audio capture are silent.
- AirPlay from iPadOS 27 depends on UxPlay; see
  [UxPlay #535](https://github.com/FDH2/UxPlay/issues/535).
- Android 14+ asks for screen-capture consent at the start of every session. That is
  an Android rule, not something Beamline can skip.

## Testing

- `protocol`: JVM unit tests for framing, CBOR messages and pairing codes.
- Device tests over adb: the Fire TV (10.10.20.150) plus both phones over wireless
  debugging. Scripts install builds, start sessions and pull logs and statistics.
- Latency: the statistics overlay plus a stopwatch-style test pattern in the sender
  app, filmed with a phone camera for a glass-to-glass check.
- CI: GitHub Actions builds all APKs and runs unit tests on every push.

## Licensing

The whole repository is GPLv3, because the TV app links UxPlay (GPLv3). Third-party
code keeps its own notices. AirPlay, Google Cast, Chromecast, Fire TV and Miracast are
trademarks of their owners; Beamline is not affiliated with them.
