# Contributing

Thanks for your interest. This is a sequenced reference build, so the
contribution model is a little different from a typical SDK.

## What contributions are welcome

- **Bug fixes** in any of the four steps
- **Improvements to the StrongBox / biometric / x402 plumbing** that
  preserve the architectural shape (e.g. better error handling, security
  hardening, test coverage)
- **New target devices** verified working — open an issue with the device
  model, Android version, and the `KeyInfo.getSecurityLevel()` log line
- **Documentation improvements** — diagrams, clarifications, fixes to the
  per-step READMEs and architecture notes
- **Reference implementations of the deferred steps** described in
  `PATH_A_NEXT.md` and `STEP_5_4_VC_PAYMENTS.md` — open an issue first to
  discuss scope

## What's likely out of scope

- **Mainnet support.** This repo is testnet-only by design and constants
  are hardcoded to Base Sepolia (chain ID 84532). Adding mainnet would
  weaken the "this is a learning artifact" framing.
- **Skipping steps.** Each step exists because the previous one's
  abstractions hide something worth feeling. PRs that collapse Steps 1–4
  into a single app will be redirected.
- **Cross-platform abstractions** (KMP, Compose Multiplatform, React
  Native) — out of scope for the canonical reference. A separate
  `react-native-omh-x402` or similar would be the right home.

## How to propose a change

1. Open an issue describing the change before opening a PR. For non-
   trivial work this saves both sides time.
2. Fork the repo.
3. Create a feature branch (`git checkout -b feature/short-description`).
4. Make your changes. Keep diffs focused — one logical change per PR.
5. Verify locally:
   - For Step 1: `cd step1-node && node pay.js <test-url>` succeeds and
     prints a real tx hash
   - For Step 2: `./gradlew :step2:run --args="<test-url>"` succeeds
   - For Steps 3 / 4: build the app, install on a StrongBox-capable
     device, complete one payment, confirm the BaseScan link works
6. For Step 4 specifically: include the `KeyInfo.getSecurityLevel()` log
   line in your PR description as evidence the change still produces a
   `STRONGBOX`-backed signature.
7. Open a PR against `main`. Reference the issue in the description.

## Commit conventions

The existing commit history follows a `Step N: <stack>, <action> <where>`
shape:

- `Step 1: Node x402 client, paid demo on Base Sepolia`
- `Step 4: Android app with StrongBox-wrapped seed, biometric-gated payment on Pixel 10 Pro`

For changes within an existing step, prefix with `Step N.X:` for sub-step
work or use a focused conventional message. For documentation-only
changes, `docs: <area>: <change>` is fine.

## Code style

- **Kotlin:** match the existing style of `step4-android-strongbox/`.
  Run `./gradlew detekt` before pushing if rules are configured for the
  module.
- **JavaScript (Step 1 only):** plain modern Node, no transpilation.
- **Comments:** explain *why*, not *what*. Code should be self-evident
  for the *what*. CLAUDE.md captures the project's full style stance.
- **No secrets in commits.** `.env`, `local.properties`, and
  `private-key*` patterns are gitignored at the root. Verify before
  pushing.

## Security

The architecture has known limits documented in the top-level README and
in `PATH_A_NEXT.md` (the brief plaintext-seed-in-RAM window during
signing). PRs that *expand* the surface where the seed is exposed
(e.g. caching it, sharing it across activities, omitting `seed.fill(0)`)
will be rejected.

For security issues that shouldn't be filed publicly, open a private
security advisory via GitHub's security tab on this repository rather
than a public issue.

## License

By contributing, you agree your contributions will be licensed under the
[Apache License 2.0](./LICENSE).
