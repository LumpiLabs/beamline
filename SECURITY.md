# Security

## Reporting a vulnerability

Please don't open a public issue. Report it privately through GitHub instead:
**Security → Report a vulnerability** on this repository. Only the maintainers see it.
Once a fix is out, the report is published with credit to you, unless you'd rather stay
anonymous.

## What to expect today

Beamline is in development, and some protection described in the
[design](docs/DESIGN.md#security-and-pairing) isn't built yet:

- The connection between the phone and the TV isn't encrypted or paired yet (planned
  for milestone M3). Anyone on the same network who can watch its traffic can see what
  is mirrored.
- While the TV app is open, any device on the network can start mirroring to it.
- With the TV app closed, a Fire TV only opens for phones that have connected before.
  It recognises them by an id the phone sends, which isn't secret, so this keeps
  strangers' phones from popping up but doesn't stop a determined attacker on the
  network.

Use Beamline on networks you trust, such as your home Wi-Fi, until pairing is in
place. Reports about these known gaps are still welcome if you find a way they're
worse than described.
