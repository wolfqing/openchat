# Security policy

## What openchat is, in one paragraph

openchat is an independent client for the bitchat wire protocol. Everything
on the wire is upstream's: the packet format, the Noise XX handshake for
private messages, the Nostr envelopes. openchat adds no cryptography of its
own. It **has not been audited by anyone**. The upstream protocol had one
third-party audit (Cure53, 2025-11) and several of its findings were still
open when this file was written. The protocol documents its own metadata
weaknesses: anyone in Bluetooth range can see that you are running it, who
you talk to on the public channel, and roughly how much you talk in private.

Do not rely on openchat where being identified has serious consequences. The
app says this on first launch, and we will keep saying it until there is an
audit that says otherwise.

## Reporting a vulnerability

Please do not open a public issue for a security problem.

Use GitHub's private vulnerability reporting on this repository
(**Security → Report a vulnerability**). If that is not possible for you,
open a public issue that says only "security, please contact me" and we will
reach out.

What helps: the openchat version (Settings shows it, or the release tag),
the Android version, steps to reproduce, and what an attacker gains. A
packet capture or a failing unit test is ideal but not required.

What you can expect:

| Step | Target |
|---|---|
| Acknowledgement | within 7 days |
| First assessment (in scope or not, severity) | within 14 days |
| Fix or mitigation in a release | within 90 days for anything rated High or above |
| Public credit | in CHANGELOG.md, if you want it |

We will tell you before we publish anything about the report. If the
problem is in the protocol rather than in openchat, we will ask whether you
want us to forward it to the upstream bitchat maintainers, and we will not
do so without your consent.

## Scope

In scope, we want to hear about it:

- Anything that lets a message be read, forged or replayed when the
  protocol says it should not be.
- Identity problems: a peer impersonating another, a fingerprint check
  that passes when it should fail, keys that leak or that are weaker than
  specified.
- The panic wipe (triple tap on the title) leaving data behind, or data
  surviving in logs, caches or backups.
- Anything that makes a phone crash or hang from a packet it received.
- Tor fail-closed being bypassed: relay traffic reaching the network
  outside Tor when the setting says Tor only.
- Build and release integrity: a published APK that does not match the
  tagged source.

Out of scope here, but still worth reporting upstream:

- Weaknesses of the bitchat protocol itself that the upstream project
  already documents (metadata exposure, passive observation over BLE,
  Sybil peers on the mesh, relay operators seeing Nostr traffic patterns).
  Report these to [permissionlesstech/bitchat](https://github.com/permissionlesstech/bitchat).
  If openchat implements the protocol differently from upstream in a way
  that makes one of these worse, that is in scope.

Out of scope entirely:

- The absence of an audit. We know.
- Reports that require a rooted or already compromised phone.
- Denial of service by jamming Bluetooth radio.
- Anything about using openchat to evade network censorship. openchat is
  not positioned for that and we do not treat circumvention failures as
  security issues.

## Supported versions

Only the latest release on the GitHub Releases page is supported. Previews
are previews; there are no security backports.

## Release integrity

Every release lists the commit it was built from and the SHA-256 of the APK
in [CHANGELOG.md](CHANGELOG.md) and in the release notes. Check the hash
before installing an APK that reached you phone to phone.

Builds after v0.5.0 are signed with a dedicated release key. Its
certificate fingerprint (SHA-256) is:

```
27:FB:F8:A4:EF:63:19:DE:61:5C:08:D9:B8:A6:76:2E:8E:2D:9F:C5:13:2C:F0:FD:22:A7:5B:4B:6F:96:EE:FE
```

Check it with `apksigner verify --print-certs <apk>`. Previews up to v0.5.0
were signed with a local debug key, which proved nothing about who built
them; a release-signed build does not install over them, so those must be
uninstalled first. The signing key and its passwords live outside the
repository. If the key is ever lost or compromised, the fingerprint here
changes and the release notes say so.

## What we will never do

- Claim security properties that have not been independently verified.
- Add our own cryptography or change the wire format in a way that breaks
  interoperability with upstream bitchat.
- Market openchat as a censorship-circumvention or anonymity tool.
