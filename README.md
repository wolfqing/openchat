# openchat

> Offline-first mesh messenger for blackouts, disasters and crowded places.
> Bluetooth LE mesh when there is no network, Nostr when there is.
> **Status: Android v0.1.0 preview. Builds and passes unit tests; not yet field-tested.**

## What this is

openchat is an open-source client for the [bitchat](https://github.com/permissionlesstech/bitchat) wire protocol: Bluetooth LE multi-hop mesh for offline messaging, Nostr relays for internet fallback, Noise Protocol encryption for private messages, no accounts, no phone numbers, no servers.

bitchat's protocol, code and whitepaper are in the public domain. openchat does not try to replace the protocol. It tries to put a different front end on the same network:

- **Built for people who have never seen IRC.** bitchat describes itself as "IRC vibes". The people who install it during an outage are not IRC people. Three taps from install to first message, with the phone offline, is the bar.
- **Positioned for infrastructure failure, not politics.** Earthquakes, typhoons, carrier outages, stadium and festival congestion, hiking and sailing. Preset channels and copy for those situations.
- **Protocol compatible, always.** An openchat phone must be a working relay for every bitchat phone and vice versa. Interop with upstream is a release gate, not a feature.
- **Honest about security.** The upstream protocol has had one third-party audit (Cure53, 2025-11) with findings still open, and documents its own metadata weaknesses. openchat will say the same thing in the app, not just in a README.

## What this is not

- Not a new protocol and not new cryptography. Everything on the wire is upstream's.
- Not a censorship-circumvention product and not marketed as one.
- Not a security product. Do not rely on it where being identified has serious consequences.

## Repository layout

```
android/                      # Kotlin app, written from scratch (see android/README.md)
docs/
  protocol-spec.md            # byte-level wire spec openchat implements
  bitchat-protocol-notes.md   # what the upstream protocol actually does, with numbers
  roadmap.md                  # phases, exit criteria and kill conditions
  phase0-findings.md          # Phase 0 review and issue analysis, go/no-go
internal/                     # private planning notes, git-ignored
```

## Roadmap in one line

Phase 0 found the UX premise weak; the project continues by the owner's decision. Android comes first, written from scratch against [docs/protocol-spec.md](docs/protocol-spec.md) with no upstream code. iOS follows. Details in [docs/roadmap.md](docs/roadmap.md).

## Name

"openchat" is a working name. GitHub already has several unrelated LLM projects under it. A final name is a Phase 0 deliverable.

## License

MIT for everything in this repository. No upstream code is included. The protocol spec was written by reading the public-domain (Unlicense) bitchat iOS source; the GPL-3.0 bitchat-android source was not read. `android/app/src/main/assets/relays.csv` is upstream's public-domain relay directory, kept so location channels use the same relays.

## Acknowledgements

Built on the work of the bitchat contributors. Not affiliated with or endorsed by them.

中文说明见 [README.zh-CN.md](README.zh-CN.md)。
