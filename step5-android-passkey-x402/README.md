# Step 5b — Path A x402 wallet (M2 + M3, in progress)

End-to-end x402 micropayment from a passkey-backed smart wallet on Base
Sepolia. Same one-tap UX as Step 4, but the on-chain signer is a smart
contract wallet and the off-chain authenticator is a P-256 passkey
that **never leaves StrongBox**.

This step lives separately from `step5-android-passkey/` (M1, hello
world) because it has its own package name (`app.x402spike.passkeyx402`),
its own minimum-viable-app surface area, and substantially more code.
Architectural plan in [`PLAN.md`](./PLAN.md).

## Status

| Milestone | Status |
|---|---|
| M2.1 — counterfactual smart-wallet address from passkey pubkey | ✅ |
| M2.2 — first UserOperation (deploy wallet via Pimlico) | ⬜ |
| M2.3 — USDC.transfer via UserOp | ⬜ |
| M3.1 — custom x402 endpoint accepting smart-account proof | ⬜ |
| M3.2 — Android client wired to that endpoint | ⬜ |
| M4 — polish + side-by-side with Step 4 | ⬜ |

## Files

| File | Purpose |
|---|---|
| `app/src/main/java/app/x402spike/PasskeyWallet.kt` | Passkey lifecycle (copied from step5 M1) |
| `app/src/main/java/app/x402spike/P256Verify.kt` | Off-chain P-256 verifier (copied from step5) |
| `app/src/main/java/app/x402spike/SmartWallet.kt` | NEW — counterfactual address via factory `eth_call` |
| `app/src/main/java/app/x402spike/MainActivity.kt` | UI: Create passkey → Compute address |
| `app/src/test/java/app/x402spike/SmartWalletTest.kt` | NEW — JVM tests for offline derivation |
| `app/src/test/java/app/x402spike/P256VerifyTest.kt` | (copied from step5) |
| `PLAN.md` | Architecture plan + open decisions for M2.2 onward |

## Verification

### Autonomous (no device needed)

```sh
cd step5-android-passkey-x402
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest    # 13/13 pass (7 SmartWallet + 6 P256Verify)
```

### On-device (M2.1 demo)

Pre-flight: this app's package is `app.x402spike.passkeyx402`. The
`assetlinks.json` at `https://wellknown-host.vercel.app/.well-known/`
must include this package + your debug keystore SHA-256, alongside the
existing entry for step5's `app.x402spike.passkey` package. Add a
second statement object to the JSON array; both apps share the host.

```sh
./gradlew :app:installDebug
adb shell am start -n app.x402spike.passkeyx402/app.x402spike.MainActivity
```

1. Tap **Create passkey** → biometric → status shows credentialId + (x, y)
2. Tap **Compute smart-wallet address** → status shows:
   - The deterministic address that wallet will live at
   - `deployed: NO (counterfactual — first UserOp will deploy)`
   - A BaseScan link
   - The packed owner bytes (X || Y)

The same passkey pubkey will *always* produce the same address. Same
across devices, same after re-installs (as long as the same passkey
exists). That determinism is what M2.2 will exploit — we faucet USDC
to the address before the wallet is even deployed.

## What "M2.1 done" means

A passkey held in StrongBox produces a counterfactual smart-wallet
address on Base Sepolia. No on-chain action, no API keys, no funding.
This is the smallest concrete step that proves the passkey-to-smart-
wallet binding works end-to-end. The next milestone (M2.2) sends the
first UserOperation that deploys the wallet and executes a no-op or
USDC.approve.

## Open decisions captured in PLAN.md

- Which smart wallet implementation (default: Coinbase Smart Wallet)
- x402 payment scheme — Path 2a (smart wallet calls USDC.transfer
  directly via UserOp) vs Path 2b (EIP-1271 delegation, blocked by
  USDC v2.2's `ecrecover`)
- Bundler choice (default: Pimlico)
- Endpoint hosting (default: self-hosted with `@x402/express`)
