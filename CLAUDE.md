# Project context for AI coding assistants

This file documents the project's design constraints and conventions. It
exists for AI coding tools (Claude Code, Cursor, Copilot, etc.) and for
human contributors who want to understand the rules of the road before
making changes.

## What this project is

A sequenced reference build of a hardware-backed Android wallet for x402
micropayments. Four steps from a Node hello-world to a Pixel app where
every payment is signed by an EIP-3009 `TransferWithAuthorization` whose
secp256k1 seed is wrapped under an AES-256-GCM key resident in StrongBox
(Titan M2 on Pixel) and unlocked only by a fresh biometric.

End-to-end goal: tap fingerprint → sign EIP-712 `TransferWithAuthorization`
for USDC on Base Sepolia → transaction lands on BaseScan.

This is a learning artifact, not production code — but no shortcuts are
taken on key handling even in test code, because the patterns matter.

## Architecture (Path B)

- secp256k1 seed generated in software via `SecureRandom`
- AES-256-GCM key in StrongBox, biometric-gated, wraps the seed
- web3j for EIP-712 encoding + ECDSA signing
- OkHttp for the x402 client
- Single-screen Android app, biometric-gated "Pay" button per signature

Path A (passkey + Coinbase Smart Wallet, fully non-extractable key) is
the structurally stronger architecture and the documented next step. See
`PATH_A_NEXT.md`.

## Sequencing — do not skip steps

1. **Node.js + `@x402/axios` script** that pays a public x402 demo endpoint.
   Verify on BaseScan. Goal: feel the protocol with no abstraction.
2. **Kotlin command-line tool** that does the same thing. Goal: hit all
   the JVM/web3j rocks before Android is in the picture.
3. **Android app with hardcoded private key.** Goal: prove the network
   plumbing, dependencies, and ProGuard work on the actual device.
4. **Replace hardcoded key with StrongBox-wrapped seed.** This is the
   demo. Biometric prompt fires, key never persists in plaintext.

Each step gets its own subdirectory and its own commit. Do not start
step N+1 until step N has been seen working and committed.

## Constraints

- **Base Sepolia testnet only.** Never mainnet in this repo.
- **Never commit private keys or seeds**, even test ones. Use BuildConfig
  fields read from a `.env` or `local.properties`, both gitignored.
- Kotlin 2.0+, JDK 17, Android Gradle Plugin 8.4+
- Target SDK 35, min SDK 28 (StrongBox API requires 28+)
- No Kotlin Multiplatform yet. Plain Android.

## What "done" looks like for each step

1. `node pay.js <url>` returns 200 after auto-paying. Print the txHash.
   Verify it on https://sepolia.basescan.org.
2. `./gradlew :step2:run --args="<url>"` does the same. Same txHash format.
3. Install on a StrongBox-capable Android device, tap button, same payment
   fires. Hardcoded key in BuildConfig (read from gitignored
   `local.properties`).
4. Same as step 3, but key is StrongBox-wrapped. Biometric prompt fires.
   `KeyInfo.getSecurityLevel()` logs `STRONGBOX` (not
   `TRUSTED_ENVIRONMENT`, not `SOFTWARE`). If StrongBox falls back to TEE,
   log it loudly and keep going — flagship Pixels sometimes do this for
   AES-GCM under load.

## Required environment

- Pixel 3+ or Samsung flagship from 2019+ (any device with
  `FEATURE_STRONGBOX_KEYSTORE`), biometric enrolled
- Android Studio recent enough for AGP 8.4+, JDK 17
- A Base Sepolia test wallet funded with USDC from
  https://faucet.circle.com and a small amount of ETH for any gas-using
  side operations
- Optional: Coinbase Developer Platform API key (free tier facilitator:
  1000 tx/month) — Step 1 demos the public faucet path; CDP credentials
  enable a one-tap recharge flow described in
  `step4-android-strongbox/CDP_FOLLOWUP.md`

## Style conventions

- **Be opinionated in code review.** Especially around key handling and
  silent StrongBox fallbacks.
- **Show diffs before applying them.** Reviewers want to read code, not
  skim.
- **One step at a time.** Do not collapse Steps 1–4 in one PR.
- **Prefer standard libraries** over esoteric ones. web3j over hand-rolled
  crypto. OkHttp over Ktor for this experiment (Ktor would come later in
  any KMP refactor).
- **Log what matters.** Every signing operation logs the security level
  of the key it used. Silent fallbacks are the enemy.
- **Comments are for "why," not "what."** The code should explain itself.

## Anti-patterns to push back on

- Putting any of this on Ethereum mainnet "just to try"
- Skipping Step 2 ("just go straight to Android")
- Using a package that hasn't been updated in 2+ years
- Hand-rolling EIP-712 encoding instead of using web3j's
  `StructuredDataEncoder`
- Storing the seed as a `String` anywhere (use `ByteArray`, zero after use)
- Using `setUserAuthenticationValidityDurationSeconds > 0` (means the
  key can be used without a fresh biometric — defeats the purpose)
- Adding KMP, Compose Multiplatform, or any cross-platform layer
  prematurely
- Building Path A (passkey/smart wallet) before Path B works end-to-end
