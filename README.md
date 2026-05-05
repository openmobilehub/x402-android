# x402-android

Hardware-backed Android wallet for [x402](https://www.x402.org) payments.
Generates secp256k1 seeds on-device, wraps them with a StrongBox-resident
AES-256-GCM key, signs EIP-3009 `transferWithAuthorization` per biometric
tap, and settles real USDC on Base Sepolia.

Built as a sequenced learning artifact in four steps so each layer's
tradeoffs are visible. Companion notes cover the path forward to passkey +
smart-wallet signing without the brief plaintext-in-RAM window the current
architecture requires.

## What's in the repo

| Step | Stack | What it does |
|---|---|---|
| 1 | Node + `@x402/axios` | Hello-world x402 client; pays a public demo endpoint, prints the on-chain tx hash |
| 2 | Kotlin CLI + web3j | Same payment, no SDK — hand-built EIP-712 signing of `TransferWithAuthorization` |
| 3 | Android (Pixel) | First app on real hardware; key hardcoded in `BuildConfig` from a gitignored `local.properties` |
| 4 | Android (Pixel) | The real demo — seed generated on-device, AES-wrapped by a StrongBox key, biometric-gated per signature, multi-wallet UI |
| 4.2 | Android (Pixel) | Local payment history with the security level captured per signature — proves `STRONGBOX` (not `TRUSTED_ENVIRONMENT`, not `SOFTWARE`) signed each tx |

Each step lives in its own subdirectory and its own commit so the
progression is reviewable as diffs. Don't skip ahead — the rocks each
step hits are part of the lesson.

## Quick proof

On a Pixel 10 Pro running Android 16, the AES wrap key for every
generated wallet lands in Titan M2:

```
I/SecureWallet: signing with key: STRONGBOX (alias=x402_seed_wrap_w_…)
```

Tapping **Pay 0.01 USDC** in the Step 4 app pops a biometric prompt,
unwraps the seed for one ECDSA signature, zeros it, and posts the
envelope. The settlement is a real on-chain transaction visible on
[BaseScan](https://sepolia.basescan.org).

### Watch the demo

<a href="https://youtube.com/shorts/gpVeYkYqaJE">
  <img src="https://img.youtube.com/vi/gpVeYkYqaJE/maxresdefault.jpg" alt="Step 4 demo: paying 0.01 USDC from a Pixel with a StrongBox-backed signature" width="320">
</a>

## Architecture (Step 4)

```
┌─────────────────────────────────────────────────────────────┐
│  StrongBox silicon (Titan M2)                               │
│    AES-256 wrap key, biometric-gated, never extractable     │
└──────────────────────┬──────────────────────────────────────┘
                       │ encrypts / decrypts
┌──────────────────────▼──────────────────────────────────────┐
│  SharedPreferences  (regular file on the phone's flash)     │
│    ct_<id>  =  base64(AES-GCM-encrypt(seed))                │
│    iv_<id>  =  base64(GCM IV)                               │
└──────────────────────┬──────────────────────────────────────┘
                       │ unwrapped briefly into RAM
┌──────────────────────▼──────────────────────────────────────┐
│  App RAM (~1 ms per signature)                              │
│    seed (32 bytes)  →  web3j EIP-712 sign  →  signature     │
│    seed.fill(0)  ← zero immediately on return               │
└──────────────────────┬──────────────────────────────────────┘
                       │ x402 envelope, base64'd, in HTTP header
┌──────────────────────▼──────────────────────────────────────┐
│  Base Sepolia                                               │
│    USDC.transferWithAuthorization()  → settled on-chain     │
└─────────────────────────────────────────────────────────────┘
```

The brief plaintext-seed window in app RAM is the only weakness of this
architecture. It's inherent — Android KeyStore (and therefore StrongBox)
doesn't support secp256k1, so the actual signing key cannot live in
silicon. The AES wrap is the closest workaround.

Eliminating that window is what `PATH_A_NEXT.md` describes: switch the
signing key to a P-256 passkey (which StrongBox does support), sign
WebAuthn assertions in silicon, and verify them on-chain via a smart
wallet contract using the [RIP-7212](https://eips.ethereum.org/EIPS/rip-7212)
P-256 precompile on Base.

## Honest about scope

**What this is:**
- A working reference for the encrypted-seed-wrap pattern most Android
  cryptocurrency wallets ship today.
- A pedagogical sequence — the value is in the diffs between steps, not
  just the final app.
- Testnet-only. Every constant is hardcoded to Base Sepolia.

**What this isn't:**
- Production-ready. The plaintext-seed-in-RAM window during signing is a
  real (if narrow) weakness; for regulated payments, identity systems, or
  custodial infrastructure, see Path A's plan in `PATH_A_NEXT.md`.
- Generic. It implements x402 specifically; nothing about the architecture
  is x402-bound, but the code is.
- A general Android wallet library. `SecureWallet.kt` is extractable into
  one (and arguably should be) but isn't packaged as such yet.

## Setup

Prerequisites:
- Pixel 3+ or Samsung flagship from 2019+ (any device with `FEATURE_STRONGBOX_KEYSTORE`)
- biometric enrolled
- Android Studio with API 35 SDK
- JDK 17

```sh
git clone https://github.com/openmobilehub/x402-android
cd x402-android/step4-android-strongbox
cp local.properties.example local.properties
# Studio populates sdk.dir automatically on first open;
# Step 4 doesn't need a PRIVATE_KEY (seed is generated on-device).
./gradlew :app:installDebug
```

For Steps 1–3, see each subdirectory's README. They each take a real
test wallet funded from [faucet.circle.com](https://faucet.circle.com)
(USDC) and an [Alchemy faucet](https://www.alchemy.com/faucets/base-sepolia)
(a tiny bit of ETH for the gas-using side ops).

## Companion documents

- [`PATH_A_NEXT.md`](./PATH_A_NEXT.md) — the next concrete steps:
  P-256 passkey via Credential Manager, smart wallet on Base, x402 wiring
- [`PATH_C_MPC.md`](./PATH_C_MPC.md) — alternative architecture, MPC
  threshold signing; deferred but documented
- [`PATH_C_WIREFRAMES.md`](./PATH_C_WIREFRAMES.md) — UX sketches for
  Path C, including the agent-management screen
- [`STEP_5_4_VC_PAYMENTS.md`](./STEP_5_4_VC_PAYMENTS.md) — bridging
  Verifiable Intent (SD-JWT) + AP2 agent flow + x402 settlement; sits
  after Path A

## License

Apache 2.0 — see [`LICENSE`](./LICENSE).
