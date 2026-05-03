# Project: StrongBox-backed x402 wallet (experiment)

## Goal

By Sunday night: tap fingerprint on my Pixel, sign an EIP-712
TransferWithAuthorization for USDC on Base Sepolia, see the
transaction land on BaseScan. End-to-end demo.

This is a personal experiment that derisks a larger strategic project
at work (Multipaz / agentic commerce gateway). Treat it as a learning
spike, not production code — but don't take dumb shortcuts on key
handling even in test code, because muscle memory matters.

## Architecture (Path B first — see research.docx)

- secp256k1 seed generated in software
- AES-256-GCM key in StrongBox, biometric-gated, wraps the seed
- web3j for EIP-712 encoding + ECDSA signing
- OkHttp for the x402 client
- Single-screen Android app, single "Pay" button

Path A (passkey + Coinbase Smart Wallet, fully non-extractable key)
is the NEXT weekend, not this one. Resist the temptation to jump.

## Sequencing — do not skip steps

1. **Node.js + @x402/axios script** that pays a public x402 demo endpoint.
   Verify on BaseScan. Goal: feel the protocol with no abstraction.
2. **Kotlin command-line tool** that does the same thing. Goal: hit all
   the JVM/web3j rocks before Android is in the picture.
3. **Android app with hardcoded private key.** Goal: prove the network
   plumbing, dependencies, and ProGuard work on the actual device.
4. **Replace hardcoded key with StrongBox-wrapped seed.** THIS is the
   demo. Biometric prompt fires, key never persists in plaintext.

Each step gets its own subdirectory and its own commit. Do not start
step N+1 until step N has been seen working and committed.

## Constraints

- **Base Sepolia testnet only.** Never mainnet in this experiment.
- **Never commit private keys or seeds**, even test ones. Use BuildConfig
  fields read from a .env or local.properties, both gitignored.
- Kotlin 2.0+, JDK 17, Android Gradle Plugin 8.4+
- Target SDK 35, min SDK 28 (StrongBox API requires 28+)
- No Kotlin Multiplatform yet. Plain Android. KMP refactor comes later
  when this gets folded into Multipaz.

## What "done" looks like for each step

1. `node pay.js <url>` returns 200 after auto-paying. Print the txHash.
   Verify it on https://sepolia.basescan.org.
2. `./gradlew :step2:run --args="<url>"` does the same. Same txHash format.
3. Install on Pixel, tap button, same payment fires. Hardcoded key in
   BuildConfig (read from gitignored local.properties).
4. Same as step 3, but key is StrongBox-wrapped. Biometric prompt fires.
   `KeyInfo.getSecurityLevel()` logs `STRONGBOX` (not `TRUSTED_ENVIRONMENT`,
   not `SOFTWARE`). If StrongBox falls back to TEE, log it loudly and
   keep going — flagship Pixels sometimes do this for AES-GCM under load.

## My environment

- Pixel 8 Pro (StrongBox-capable, biometric enrolled)
- macOS, Android Studio Hedgehog or newer, JDK 17 (sdkman or brew)
- Coinbase CDP account with API key (free tier facilitator: 1000 tx/month)
- Test wallet funded with Base Sepolia USDC from https://faucet.circle.com
- Tiny amount of Base Sepolia ETH for any gas-required side ops

## Reference

Full research, rationale, MPP context, framework comparisons, and the
NotebookLM podcast prompt are in `research.docx` in this directory.
The "why" is there; this CLAUDE.md is the "what" and "how."

## Style preferences

- **Be opinionated.** If I'm about to do something dumb, push back.
  Especially around key handling and silent StrongBox fallbacks.
- **Show me the diff before applying it.** I want to read code, not skim.
- **One step at a time.** Do not try to build steps 1–4 in one PR.
- **Prefer standard libraries** over esoteric ones. web3j over hand-rolled
  crypto. OkHttp over Ktor for this experiment (Ktor comes later in KMP).
- **Log what matters.** Every signing operation should log the security
  level of the key it used. Silent fallbacks are the enemy.
- **Comments are for "why," not "what."** The code should explain itself.

## Things to push back on if I propose them

- Putting any of this on Ethereum mainnet "just to try"
- Skipping step 2 ("I'll just go straight to Android")
- Using a package that hasn't been updated in 2+ years
- Hand-rolling EIP-712 encoding instead of using web3j's StructuredDataEncoder
- Storing the seed as a String anywhere (use ByteArray, zero it after use)
- Using `setUserAuthenticationValidityDurationSeconds` > 0 (means the key
  can be used without a fresh biometric — defeats the purpose)
- Adding KMP, Compose Multiplatform, or any cross-platform layer this week
- Building Path A (passkey/smart wallet) before Path B works end-to-end
