# Security

## Reporting a vulnerability

Please don't open a public issue. Report it privately through GitHub instead:
**Security → Report a vulnerability** on this repository. Only the maintainers see it.
Once a fix is out, the report is published with credit to you, unless you'd rather stay
anonymous.

## How Beamline protects a session

The details are in the [design](docs/DESIGN.md#security-and-pairing).

- Everything between the phone and the TV, picture and sound included, is encrypted
  with TLS 1.2 or newer. Each phone and each TV has its own key, made on the device and
  never backed up or sent anywhere.
- The first time a phone and a TV meet, both show the same 6-digit code. The phone's
  user confirms that the codes match, and the TV's user allows the phone with OK on
  the remote. A device sitting in between would cause different codes.
- After that, the two recognise each other by their keys. With the TV app closed, a
  Fire TV only lets paired phones in.

## Known limits

Beamline is still in development.

- The TV can't yet forget a single paired phone; clearing the TV app's data forgets
  all of them (and the phone app's data, all TVs).
- Whoever can press OK on the TV remote while a new phone pairs decides whether it may
  mirror. That is on purpose, as for any TV.
- The apps haven't had an independent security review.

Reports about these limits are welcome too if you find a way they're worse than
described.
