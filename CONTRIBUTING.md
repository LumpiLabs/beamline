# Contributing to Beamline

Thanks for helping. Beamline is a small project worked on in spare time, so an issue or
pull request may wait a few days for an answer.

## Reporting a problem

Open an issue with the bug form. The most useful things to include:

- The phone and the TV: model, Android or Fire OS version, and how they're connected
  (2.4 or 5 GHz Wi-Fi, Ethernet).
- What you mirrored: the app, and the video's frame rate if you know it.
- A photo of the TV's statistics: press Menu on the remote while mirroring.
- Logs, if you can use adb. Right after the problem, run this for each device and
  attach the files. Look through them first: they can contain other apps' messages.

  ```bash
  adb logcat -d -v time > beamline.log
  ```

  Beamline's own lines all start with `Beamline` (for example `BeamlineServer`,
  `BeamlineAudio`, `BeamlineSender`).

Security problems go to [SECURITY.md](SECURITY.md), not to public issues.

## Building and testing

You need JDK 21 and the Android SDK (platform 37). The [README](README.md#building) has
the details.

```bash
./gradlew build
```

This builds both apps and runs the unit tests and lint; CI runs the same on every push
and pull request. To try the TV app without a phone, use the test sender described in
the README. `--hiccup=<second>` simulates Wi-Fi that stalls for a moment.

## Changing code

Changes reach `main` only through pull requests, and each one is merged by the
maintainer after their approval and a green build. For contributors from outside the
project, the build starts once the maintainer has looked at the pull request.

- One topic per pull request. Small ones get reviewed fastest.
- `./gradlew build` must pass: tests and lint included.
- Write like the code around you: Kotlin with 2-space indents, comments that say why
  rather than what, plain words in user-facing text. Add a dependency only with a good
  reason.
- Changes that users or the other device notice go into [docs/DESIGN.md](docs/DESIGN.md),
  and into [CHANGELOG.md](CHANGELOG.md) under "Unreleased".
- Keep the wire protocol compatible: new fields in control messages get defaults, and
  unknown fields and messages are ignored. Raise `Protocol.VERSION` only for changes
  that older apps can't handle.
- Commit messages: a short summary line, then what changed and why.

## Translations

Each app keeps its English text in `src/main/res/values/strings.xml` and the German
text in `values-de/`. To add a language, copy `values/strings.xml` to
`values-<language code>/` and translate every string not marked
`translatable="false"`; lint reports any you missed. The store texts are in
`sender/fastlane` and `receiver/fastlane`.

## License

Contributions are released under the [GPLv3](LICENSE), like the rest of Beamline.
