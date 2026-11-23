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
 Galaxy S26+ ─Beamline protocol┤   ├─ AirPlay receiver (UxPlay via JNI)
 Windows laptop ─Beamline prot.┘   └─ Beamline receiver (Kotlin)
```

Gradle modules:

| Module | Kind | Purpose |
|---|---|---|
| `protocol` | Kotlin/JVM library | Message types, framing, versioning, pairing code derivation. No Android dependencies, so it is unit-tested on the JVM. |
| `sender` | Android app, minSdk 29 | Runs on the phones. Screen capture, encoding, discovery, pairing, Quick Settings tile. |
| `receiver` | Android TV app, minSdk 25 | Runs on the Fire TV. Discovery advertisement, decoding, playback, TV UI. |
| `windows-sender` | Kotlin/JVM app with a C++ library | Runs on Windows laptops. Screen and sound capture, hardware encoding, discovery, pairing. |
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
peers can add both. Messages: `hello` (versions, device name, capabilities, a
`sender_id` that stays the same for one install of the sender app, `pair` when the
sender doesn't know the receiver yet, `low_latency` for presenting and games, see
Delay, and `reconnect` after a lost connection, see Sessions), `pairing` and
`pairing_confirm` (see below),
`welcome` (chosen version, stream limits, and the audio and video codecs the receiver
plays; a receiver that lists no video codecs takes H.264), `stream` (codec, width, height, fps,
rotation, sent before each new video config), `request_keyframe`, `bitrate_hint`,
`ping`/`pong` (round-trip time and clock offset), `stats`, and `bye` (with reason; the
receiver's reasons "busy", "replaced", "open_app", "app_closed", "no_screen" and
"pairing_declined" get their own message on the phone, others are shown as they are;
the sender says "pairing_rejected" when its user saw a different code).
Both sides ping once a second. The sender's `time_us` in pings and pongs is on the
same clock as its `pts_us`, so the receiver can work out from the round trips how far
its picture is behind the sender's screen.

### Security and pairing

Each install generates an EC P-256 key pair and a self-signed certificate on first
start and keeps them in `no_backup` storage, so a backup can't carry them to another
device. The certificate is written by a small DER encoder in `protocol`, as the JDK
has no public API for it. Sessions use TLS 1.2 or newer with mutual certificate
authentication. The handshake accepts any certificate, since there is no authority
that could vouch for a TV or a phone; trust comes afterwards from the peer's
certificate fingerprint (SHA-256 of the DER) and, the first time, from pairing.

On the first connection between a phone and a TV, both screens show a 6-digit code:

```
h    = SHA-256("beamline-pairing-v1" || u32(len(r)) || r || u32(len(s)) || s)
code = (first 4 bytes of h as unsigned big-endian integer) mod 1,000,000, zero-padded to 6 digits
```

where `r` and `s` are the DER certificates of the receiver and the sender. Test
vector: `r = 01 02 03`, `s = 04 05 06` gives `939459`. This is the same
numeric-comparison idea Bluetooth pairing uses: a device in the middle would
produce different codes on the two screens.

The flow, right after the TLS handshake:

1. The sender sends `hello`, with `pair` if it doesn't know the receiver's fingerprint.
2. If the receiver doesn't know the sender, or `pair` is set, it answers `pairing`
   instead of `welcome` and shows the code. `receiver_confirms` says whether the TV's
   user must allow the sender too, which is the case when the TV doesn't know it.
3. On the phone, a sender that doesn't know the TV asks its user whether the TV shows
   the same code, in the app and in a notification (mirroring often starts from the
   Quick Settings tile). On a yes, or at once when it knows the TV, it sends
   `pairing_confirm`; on a no it sends `bye` "pairing_rejected".
4. On the TV, OK allows the phone and Back declines it (`bye` "pairing_declined").
   After OK, the TV asks to confirm the code on the phone while that answer is still
   open, so its user can tell OK was taken.
5. With both answers in, the receiver remembers the sender's fingerprint and sends
   `welcome`, and the sender remembers the receiver's. Later connections start without
   a prompt.

Both answers matter. The phone's keeps it from sending its screen to a device in
between; the TV's keeps strangers on the network from pairing with it on their own,
which a confirmation on the phone alone couldn't stop. Each side waits two minutes
for its user. Pairing needs the TV app on screen, so with the app closed only paired
phones get in. The TV's settings list the paired phones; one forgotten there has to
pair again.

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
  phone in landscape keeps full detail when the TV fills its screen. The highest
  bitrate is about 1/16 bit per pixel per frame, between 2 and 12 Mbit/s (about
  9 Mbit/s for a landscape phone).
- **Weak Wi-Fi:** frames go out on a thread of their own, from a send queue where
  control messages and sound overtake waiting video, through a socket whose send buffer
  follows the bitrate: about a tenth of a second of video once Linux has doubled it
  (between 8 and 64 KB). A slow network then shows up as frames waiting in the queue,
  where they can still be skipped, instead of video hidden in the system's buffer. Once the oldest
  waiting video frame is 250 ms old, the waiting video is dropped, the phone skips the
  encoder's output until the next keyframe and asks for one, and the bitrate goes down
  to 60%; if frames waited more than 80 ms in the last second, it goes down to 80%. One
  stall shows up several times (as skipped video, in the next second's waits, in
  keyframes skipped while it lasts), so cuts within a second of each other don't add
  up: each is taken from the rate before the first, and the deepest counts. After calm
  stretches, with every frame out within 20 ms, the bitrate comes back 15% every 3
  seconds, starting 10 seconds after skipped video or 5 seconds after a backlog, up to
  the stream's highest. From 85% of the rate at which frames last backed up it goes on
  only 5% every 5 seconds, since a big step there would likely back them up again and
  the TV may show that as a stutter; 15% past that rate, the big steps come back. It
  never goes below an eighth of the highest or 1 Mbit/s, and keeps its share of the
  highest when the stream restarts with a new size.
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
  `mediaProjection`) with a notification that has a Stop action and follows the
  session: connecting, the pairing question, allowing on the TV, mirroring,
  reconnecting. It never says it mirrors before it does. The pairing question goes on
  a channel that pops up, with both answers on the notification itself; a separate
  notification for it was missed on a Pixel, where Android folded it away.
- **Keep awake:** once the phone locks, Android stops sending the screen's picture, so
  while mirroring the service keeps the screen on, dimmed, with a screen wake lock
  (`FLAG_KEEP_SCREEN_ON` would need a Beamline window on screen, but the user is in
  another app). On by default; a switch in the app turns it off, also mid-session.
- **Quick Settings tile:** one tap mirrors to the last TV used (after Android's
  screen-sharing prompt), another tap stops; with no TV used yet it opens the app. On
  Android 13 and newer the app offers to add the tile. A small invisible activity shows
  the prompt, because a tile can't show it itself.

## Sender (Windows)

A Kotlin/JVM app that shares `protocol` with the phone, so pairing, TLS, the send queue
and the bitrate rules are the same code. Capture and encoding live in a small C++
library (`beamline-media.dll`, built with MinGW-w64 and called through JNA), because
they are Windows COM APIs:

- **Capture:** Desktop Duplication of one screen, on the GPU that shows it. A D3D11
  video processor scales the picture to the stream size and converts it to NV12
  (BT.709, TV range). Pictures come when the screen changes, at most at the stream's
  frame rate, stamped with the time Windows showed them; a still screen sends its last
  picture again every 100 ms, as the phone does. The mouse cursor isn't drawn. While
  Windows doesn't let the screen be captured (locked, a UAC prompt), the TV keeps the
  last picture and capture resumes afterwards; a new resolution starts a new stream.
- **Encoding:** the Media Foundation H.264 encoder of that GPU (NVIDIA, AMD or Intel),
  or Windows' software encoder without one. Constant bitrate, low-latency mode, no
  B-frames, a keyframe every 10 seconds or on request
  (`CODECAPI_AVEncVideoForceKeyFrame`), and bitrate changes while it runs
  (`CODECAPI_AVEncCommonMeanBitRate`), so the send queue and the bitrate rules work as
  on the phone. Stream size, frame rate and highest bitrate are the phone's too.
- **Sound:** WASAPI loopback of the default output device, 48 kHz stereo, stamped with
  the device's own timestamps on the performance counter, the clock the pictures and
  pings use. Windows delivers nothing while nothing plays, so silence fills the gaps
  and the TV's sound clock keeps running. Opus at 128 kbit/s in 20 ms frames, with
  libopus.
- **Wi-Fi:** while mirroring, the Wi-Fi adapter is in Windows' media streaming mode
  (`WlanSetInterface`), which keeps it out of power saving, as the TV's Wi-Fi lock does
  there. Windows undoes it when mirroring stops.
- **UI:** a small window that lists the TVs found over DNS-SD (with JmDNS, on every
  private IPv4 network, so a VPN such as Tailscale doesn't hide the home network) and
  takes an address by hand, the screens, the low-latency switch, Start and Stop, and
  the pairing question. `--tv=<address>` mirrors from the console instead. The key,
  the paired TVs and the choices are kept in `%APPDATA%\Beamline`, with a log of the
  last run.

## Receiver (TV)

- **Video:** `MediaCodec` decoding straight to a `SurfaceView`. Compressed frames wait
  in a buffer and go to the decoder only as long before they are due as decoding takes
  (measured all the time, plus a margin: 60 to 100 ms), so the decoder never runs out of
  output slots; each decoded frame is released for its exact display time
  (`releaseOutputBuffer` with a timestamp). Display times follow the sound's clock, and
  are smoothed into an even cadence, because a phone composing 25 fps video on a
  120 Hz screen delivers frames alternately 33 and 42 ms apart.
- **Decoding without holding back:** the Fire TV Stick 4K Max's MediaTek decoder hands a
  picture out only once one or two more have gone in, in case one has to be shown
  before the other: 40 to 80 ms at 25 fps, and on a still screen the last change never
  shows. Two things stop that, and only together: Fire OS's own low-latency switch (the
  format key `vdec-lowlatency`, found in its `libstagefright`, which turns on
  `OMX.amazon.fireos.index.video.lowLatencyDecode`; Android 11 calls it
  `KEY_LOW_LATENCY`), and an H.264 SPS whose VUI bitstream restriction says that
  pictures are never reordered (`max_num_reorder_frames` 0, `max_dec_frame_buffering`
  as small as the reference pictures allow). The Galaxy's encoder writes that; others
  don't, so the TV rewrites every SPS it gets that lacks it. Decoding a frame then takes
  5 to 10 ms instead of 40 to 85.
- **Catching up:** a frame that comes out of the decoder more than 40 ms late is
  skipped, never two in a row. Content at the screen's own rate (60 fps on 60 Hz) can't
  otherwise catch up after a stall, such as the TV app opening while frames already
  arrive, and the picture would stay behind the sound for good.
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
- **Settings:** with no phone connected, OK or Menu opens the settings, worked with the
  D-pad: fill the screen, show the statistics, and the paired phones with when each
  last connected. Forgetting one asks first, with Cancel picked. Each connection
  updates the phone's name and last use. A phone connecting closes the settings.
- **Ready in the background:** on Fire OS 6 and 7 (Android 7.1 to 9) a foreground
  service keeps the server and the network announcement up while the TV app is
  closed; it starts when the app is first opened and again after the TV boots. A paired
  phone can then start mirroring with the app closed: the TV wakes, also from its
  screensaver, and brings the app to the front, and the session waits up to 4 seconds
  for the screen. Phones that still have to pair get `bye` "open_app" and are told to
  open Beamline on the TV first. Android 10 and newer don't let an app open itself from the
  background without the "display over other apps" permission, so there the server
  runs only while the app is open.
- **Leaving the app:** Home or Back while mirroring ends the session with `bye`
  "app_closed", so the phone stops and says why instead of reconnecting and pulling
  the user back into the app.
- **Sessions:** one active stream at a time. A second sender gets `bye` "busy", and
  the phone says the TV is showing another device. With "Let another phone take over"
  on in the TV's settings (off by default), a paired phone that starts mirroring
  replaces the one on screen instead, which gets `bye` "replaced" and says another
  device took over. A phone that still has to pair gets "busy" either way, because
  pairing needs the TV's screen. The TV ends a session after 10 seconds without data.
  A phone that comes back sooner (same `sender_id`) replaces its old connection right
  away, and the last picture stays on screen until the new one arrives. A phone coming
  back (`reconnect`) never takes over from another one: otherwise a phone back from a
  Wi-Fi hiccup would push out the phone that took over meanwhile. It gets "replaced"
  instead, and any "no" from the TV while reconnecting ends the session on the phone.
- **Wi-Fi:** while a phone is connected, the TV holds a Wi-Fi lock (low-latency mode
  from Android 10, high-performance mode before), so its Wi-Fi stays out of power
  saving, where the access point holds frames back and delivers them in bunches.
- **AirPlay (M4):** UxPlay's server runs alongside the Beamline receiver and renders
  into the same player screen, so an iPad and an Android phone use the same TV app;
  see AirPlay.

## AirPlay (M4)

Status: plan, 2026-11-23. M4 comes in steps, each its own pull request:

1. **Mirroring starts:** the TV shows up under Screen Mirroring on a Mac and an iPad,
   and a session runs until decrypted video reaches the TV app (checked in the log).
2. **Picture and sound:** the video goes into `VideoDecoder`, the sound (AAC-ELD while
   mirroring) into `AudioPlayer` through `MediaCodec`, in sync, with the same busy and
   take-over rules as a Beamline phone (`Admission`).
3. **PIN:** the first time an Apple device connects, the TV shows a PIN to type on it
   (UxPlay supports this); afterwards the TV remembers it.
4. **AirPlay video:** the AirPlay button in a video app sends the video's address, and
   the TV plays the HLS stream itself with Media3.

### Module `airplay`

An Android library with native code, built with the NDK and the CMake from the Android
SDK for armeabi-v7a (Fire OS 7 has a 32-bit userland) and arm64-v8a, minSdk 25. Its
native sources are pinned git submodules, built by our own CMake lists:

| Submodule | Pinned to | License | Used for |
|---|---|---|---|
| UxPlay | `master` at 48f5ce4 (2026-11-17) | GPLv3; parts of `lib/` LGPL-2.1, llhttp MIT | The AirPlay protocol: RTSP, pairing, FairPlay, mirror and audio streams. Only `lib/`, without its `dns_sd` and `mdnsd` backends. |
| BoringSSL | a `main` commit | Apache 2.0 | libcrypto: AES-CTR/CBC/GCM, SHA, HMAC, Ed25519, X25519, BN for SRP. Builds with CMake alone (pre-generated sources), no Perl or Go. |
| libplist | 2.8.1 | LGPL-2.1 | The binary plists in AirPlay's requests. Its own build uses autotools, so we list its sources in CMake. |

UxPlay's `master` instead of v1.73.7, because it has the iOS 27 fixes since that
release and FairPlay v2 (#639). The submodules are changed only by moving their pin;
anything UxPlay needs from us goes in our own sources (the mDNS backend, the JNI glue,
if needed a small compatibility header for BoringSSL).

### Announcing the TV

The TV announces `_airplay._tcp` (as "<TV name>") and `_raop._tcp` (as
"<device ID>@<TV name>") through `NsdManager`, as it already does `_beamline._tcp`,
instead of running a second mDNS responder next to Fire OS's. For this, a small dnssd
backend of our own implements UxPlay's `dnssd_private_init` and the register calls:
it builds the same TXT records as UxPlay's backends and hands them, with the port,
over JNI to Kotlin, which registers them. The device ID AirPlay wants is a MAC address,
which Android doesn't reveal, so the TV makes up a random, locally administered one on
first start; it and UxPlay's Ed25519 key are kept in `no_backup` storage, so Apple
devices keep recognising the TV across restarts but not on another device.

The TV offers H.264 and H.265 (feature bit 42, SupportsScreenMultiCodec): iOS 27
clients have been seen to send H.265 and fail without it, and the Fire TV Stick 4K
Max decodes both in hardware. It asks for 1920×1080 at 60 fps to start with.

### Kotlin side

`AirPlayServer` in the `airplay` module starts and stops UxPlay's server (TV name,
device ID, key directory) and reports through a listener: the services to announce,
a client connecting and leaving (with its name), video (H.264 or H.265 NAL units,
Annex B, with UxPlay's timestamp) and sound (format and data). The JNI glue attaches
UxPlay's threads to the JVM and copies each buffer once. `ReceiverController` runs it
next to `MirrorServer` with the same name; in step 1 the listener only logs (codec,
picture size from the SPS, frames and bytes per second, sound format).

### CI

The checkout fetches the submodules, and the workflow installs the pinned NDK and
CMake (`ndkVersion` in `airplay/build.gradle.kts`) with the SDK's `sdkmanager` before
the Gradle build.

### Testing

A Mac (macOS 27.0.1) and the iPad mirror through Control Center → Screen Mirroring →
the TV. Step 1 is done when `adb logcat -s BeamlineAirPlay` shows the parameter sets
and keyframes of a session, then a steady stream of frames.

Seen so far with a MacBook Pro on macOS 27.0.1 (AirPlay 980.77.5) and the Fire TV Stick
4K Max: the Mac lists the TV, mirroring starts, and the TV app gets H.264 at
1920×1080, a keyframe with its parameter sets first, then 30 to 60 frames per second;
sound comes as AAC-ELD (480 samples per frame) once something plays. Stopping on the
Mac ends the session cleanly, and mirroring again right after works. The TV keeps
UxPlay's key across restarts.

### Risks

- [UxPlay #535](https://github.com/FDH2/UxPlay/issues/535): with iOS 27, mirroring is
  negotiated but the client never opens the data connection. Still open; the
  maintainer thinks it fixed on `master`. If it shows up here, UxPlay's debug log
  goes to the issue.
- UxPlay is written against OpenSSL 3. BoringSSL has all the functions it calls
  (listed above), but small differences may need the compatibility header.
- Fire OS's `NsdManager` may refuse some TXT values; if so, step 1 shows it first.

## Delay

Smooth playback comes first: the TV keeps about a third of a second of sound queued,
which hides Wi-Fi hiccups of that length without a stutter, and shows each picture with
its sound. The Fire TV Stick 4K Max's own sound output adds about a quarter of a second
more: with a Galaxy S26+, the picture was 568 ms behind the phone, with 332 ms of sound
queued. Picture and sound stay in sync with each other; only the phone itself is ahead.
On a Wi-Fi too slow for the stream, the phone lowers the bitrate and skips video rather
than letting the delay grow (see Weak Wi-Fi under Sender).

**Low latency**, for presenting and games, is a switch in the phone app. It goes to the
TV in `hello` (`low_latency`), so it applies from the next start. Then:

- The TV shows each picture 60 ms after the fastest delivery of the last few seconds,
  with or without sound: enough to decode it, with a little to spare for frames that
  take longer. A later frame is shown as soon as it is decoded, or skipped when far
  behind (see Catching up).
- The sound gets an 80 ms cushion instead of 300 ms, and the picture doesn't wait for
  it, so the sound trails the picture by the TV's own sound output.
- The phone sends H.264, which the TV decodes without holding pictures back (see
  Decoding without holding back), and skips waiting video once it is 100 ms old
  instead of 250.

From the Windows app on a laptop, the picture is 505 ms behind the laptop with sound,
and 75 to 85 ms in this mode.

From the PC test sender (no capture or encoding), the picture is 63 to 65 ms behind the
sender in this mode at 25 and 60 fps, against 256 ms without it and without sound, or
482 ms with sound. With sound, the sound trails the picture by 195 ms, about 55 ms of
it queued, and a Wi-Fi hiccup that empties the queue costs a gap of a few hundred
milliseconds.

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
- The Windows app mirrors whole screens, without the mouse cursor; HDR and rotated
  screens aren't supported yet. Until the release bundles one, it needs Java 21 or
  newer.

## Testing

- `protocol`: JVM unit tests for framing, CBOR messages, pairing codes, the send queue
  and the bitrate rules.
- Device tests over adb: the Fire TV plus both phones over wireless
  debugging. Scripts install builds, start sessions and pull logs and statistics.
- `tools/test-sender` streams a test pattern, optionally with sound from an Ogg Opus
  file, to the TV from a PC, so the TV app can be checked without a phone. With
  `--hiccup=<second>` it stalls for a second and then sends everything at once, like
  Wi-Fi; `--hiccup=0` gives the burst a reconnecting phone causes. `--low-latency`
  asks for the low-latency mode, and a low frame rate (`2` for the fps) shows whether
  the TV's decoder holds pictures back: the overlay's decode time then jumps to 500 ms
  or more. Every run is the same sender; `--sender=<name>` connects as another one,
  like a second phone, and `--reconnect` as one coming back after a lost connection,
  for trying the busy handling.
- The Windows app's `--record=<file>` encodes the screen into an H.264 file and
  reports the frames and the sound level that came in, for checking capture without a
  TV.
- Weak Wi-Fi: debug builds of the TV app read the phone's stream no faster than
  `adb shell am start -n io.github.lumpilabs.beamline.receiver/.MainActivity --ei
  debug_read_kbps 3000` allows (kbit/s; 0 is full speed again), keeping a small
  receive buffer meanwhile so the phone notices at once, as it would on a weak Wi-Fi.
  The phone logs each bitrate change and why.
- Latency: the statistics overlay plus a stopwatch-style test pattern in the sender
  app, filmed with a phone camera for a glass-to-glass check.
- CI: GitHub Actions builds all APKs and runs unit tests on every push.

## Licensing

The whole repository is GPLv3, because the TV app links UxPlay (GPLv3). Third-party
code keeps its own notices. AirPlay, Google Cast, Chromecast, Fire TV and Miracast are
trademarks of their owners; Beamline is not affiliated with them.
