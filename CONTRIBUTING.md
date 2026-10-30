# Contributing to openchat

Thanks for looking. openchat is small and has one maintainer, so this file
is short and the rules in it are firm. Most of them exist to protect the
licence or the interoperability promise, and we will not bend them for a
feature however good it is.

## The three rules

### 1. Clean room: never read bitchat-android

openchat is MIT. It can be MIT only because nobody who wrote it has read
[bitchat-android](https://github.com/permissionlesstech/bitchat-android),
which is GPL-3.0. One contributor who has read that code and then writes a
similar function here would put the licence of the whole project in doubt.

So:

- **If you have read bitchat-android source code, you cannot contribute
  Kotlin to this repository.** Documentation, translations, issue reports
  and test ideas are still welcome.
- For protocol questions, the references are
  [docs/protocol-spec.md](docs/protocol-spec.md) and the upstream iOS
  source, which is public domain (Unlicense). Read those. Do not look at
  how the Android upstream did it.
- If you find the spec wrong or incomplete, say so in an issue. Fixing the
  spec is a contribution in itself.

Every pull request that touches code must include the sentence
"I have not read bitchat-android source code" in its description. We will
ask if it is missing.

### 2. Interoperability is a release gate

An openchat phone must be a working relay for every stock bitchat phone and
vice versa. Public messages, private messages, courier envelopes and
location channels must all work against an unmodified upstream client.

A change that breaks this does not ship, whatever else it improves. If you
want to add something to the wire, the place to propose it is the upstream
protocol, not here. openchat invents no cryptography and no packet types.

### 3. Positioning is not negotiable

openchat is built for infrastructure failure: blackouts, earthquakes,
typhoons, carrier outages, crowded venues. It is **not** a
censorship-circumvention tool and is not marketed as one. It is **not** a
security product and does not claim security that has not been audited.

We will decline features, copy, screenshots and README changes whose main
purpose is circumvention or anonymity, even when they are technically
sound. Routing over Tor exists because relay operators should not see your
IP, not as a political statement. Keep it that way.

## Sign your commits (DCO)

There is no contributor licence agreement. Instead, every commit carries a
[Developer Certificate of Origin](https://developercertificate.org/)
sign-off, which says you have the right to contribute the change under the
MIT licence:

```
git commit -s
```

That adds `Signed-off-by: Your Name <you@example.com>`. Pull requests with
unsigned commits will be asked to rebase.

## What we are glad to receive

- **Bug reports with a device.** Phone model, Android version, openchat
  version, what you did, what happened. For mesh problems, say what the
  other phone was running (openchat version or bitchat version) and how far
  apart the phones were. Logcat helps.
- **Interop reports.** Anything that works with another openchat phone but
  not with stock bitchat, or the other way round, is a priority bug.
- **Translations.** Strings live in `android/app/src/main/res/values-*/`.
  Copy `values/strings.xml`, translate, keep the placeholders. Short beats
  literal: these strings are read on a phone during an outage.
- **Spec corrections.** If a packet from a real bitchat phone does not match
  [docs/protocol-spec.md](docs/protocol-spec.md), that is a spec bug and we
  want the bytes.
- **Tests.** Especially codec round-trips and anything that catches a
  divergence from upstream.
- **Field notes.** You tried it at an event, on a hike, in a building
  during a drill. How many phones, how far, what failed.

## What to ask about first

Open an issue before starting on any of these, so nobody wastes a week:

- New screens or changes to the main flow. The UI direction is in
  [docs/design/ui-direction.md](docs/design/ui-direction.md); proposals
  that do not fit it need a conversation first.
- Anything touching `crypto/`, `core/Codec.kt` or `mesh/MeshEngine.kt`.
- New dependencies. Every library must be compatible with an F-Droid build
  (no proprietary or Google Play Services code) and must earn its APK size,
  because the APK is shared phone to phone over Bluetooth.
- Anything the roadmap lists under "Not yet" in
  [android/README.md](android/README.md).

## Pull request checklist

- [ ] Commits are signed off (`git commit -s`).
- [ ] The description says "I have not read bitchat-android source code".
- [ ] `./gradlew :app:testDebugUnitTest` passes.
- [ ] For wire-level changes: tested against a stock bitchat phone, and the
      description says which version.
- [ ] User-facing strings are in `strings.xml`, not hard-coded, and the
      English source string is updated.
- [ ] No new permission without a sentence in the description saying why.
- [ ] CHANGELOG.md has a line under the next version if a user would notice
      the change.

## How decisions get made

One maintainer decides, and writes the reasons down. The roadmap, including
the conditions under which the project stops, is public in
[docs/roadmap.md](docs/roadmap.md). Phase findings are in `docs/`. If you
disagree with a decision, open an issue and argue the case; "upstream does
it differently" and "this breaks interop" are the two arguments that win
most often.

## Security issues

Not here. See [SECURITY.md](SECURITY.md).

## Licence

By contributing you agree that your contribution is licensed under the MIT
licence in [LICENSE](LICENSE), the same as the rest of the project.
