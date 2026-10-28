# Roadmap

Three phases. Each has an exit criterion and a kill condition. Nothing in
Phase 1 starts until Phase 0 says the premise holds.

## Premise being tested

1. The people who install bitchat during an outage are not IRC users, and
   the current UI loses them. (Hypothesis. No direct evidence yet; upstream
   is still fixing basic UX in v1.8.0, which is suggestive, not proof.)
2. Infrastructure failure (carrier outages, earthquakes, typhoons, stadium
   congestion) is a large enough non-political market for a mesh messenger
   to be positioned there and stay in app stores.
3. Protocol compatibility with bitchat gives a new client a live relay
   network on day one, so the classic mesh-messenger cold-start problem does
   not apply.

## Phase 0 — validate, no code (about 1 week)

- Pull App Store reviews for bitchat in US, FR, IN and JP, plus r/bitchat
  and upstream GitHub issues. Classify complaints; measure the share that is
  onboarding or UI confusion versus connectivity or security.
- Field test upstream on two iPhones and one Android, airplane mode: count
  taps and seconds from install to first delivered message, for public
  channel, private message and geohash channel. Record every point where a
  non-technical person would stop.
- Decide the Android licensing path: bitchat-android is GPL-3.0, so a fork
  is GPL-3.0 and a clean-room rewrite from the whitepaper is the only MIT path.
- Pick the final name. "openchat" collides with several LLM projects.

**Exit**: a written one-page finding with the complaint share, the tap
count, and a go/no-go.

**Kill**: UX complaints are a minority of feedback, or upstream ships a
rebuilt onboarding for non-technical users during this phase.

## Phase 1 — iOS client (2–4 weeks)

- Fork upstream iOS. Keep `Noise/`, `Protocols/`, `Sync/`, `Nostr/`,
  `Identity/` and `BitFoundation` unchanged. Pull upstream regularly.
- Rebuild: onboarding, the main screen, offline-state communication, and
  preset channels for the target scenarios (carrier outage, earthquake,
  typhoon, event venue).
- Put the security caveat inside the app, in plain language, where a
  first-time user sees it.
- Interop gate: every build must exchange public messages, private messages
  and courier mail with an unmodified upstream bitchat on another device,
  and pass upstream's courier test vectors.

**Exit**: TestFlight build that a non-technical tester can use to send a
message to a stock bitchat phone within three taps, offline.

**Kill**: cannot hold interop while changing the UI, or upstream changes the
wire protocol (peer ID rotation) faster than this fork can follow.

## Phase 2 — Android and a real scenario

- Android: fork bitchat-android under its license, or re-implement from the
  whitepaper if GPL-3.0 is not acceptable for this project.
- Pilot in one predictable scenario: a disaster-preparedness drill, a large
  event, or a known congested venue. Measure whether strangers actually form
  a mesh and whether the three-tap claim survives contact with real users.

**Exit**: one pilot with written results.

**Kill**: nobody outside the project's own devices forms a mesh.

## Standing rules

- Never invent cryptography. Everything on the wire is upstream's.
- Never market as censorship circumvention.
- Never claim security that upstream has not had audited.
- Interop with upstream is a release gate.

## Decay signals to watch

- Upstream rebuilds its UX for non-technical users.
- Upstream wire-protocol changes that compatible clients cannot follow.
- Developed-country carrier outages (the France 2026-10 pattern) do not
  recur; the infrastructure-failure positioning then rests on disasters
  alone.
