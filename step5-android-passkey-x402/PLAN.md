# Step 5b — Path A x402 wallet (M2 + M3)

End-to-end Android app: passkey-signed x402 micropayment via a smart
contract wallet on Base Sepolia. This is the "real" Path A application
that combines `step5-android-passkey/`'s M1 (passkey hello world) with
the smart-wallet (M2) and x402-wiring (M3) milestones from
`../PATH_A_NEXT.md`.

## What this proves

A USDC payment lands on BaseScan, signed by a P-256 key that **never
leaves StrongBox** at any point:
- No seed in JVM heap (Step 4's residual weakness)
- No secp256k1 anywhere on-device (irrelevant — we're on P-256)
- The on-chain signer is the smart contract wallet; the off-chain
  authenticator is the StrongBox-resident passkey
- Same biometric-tap UX as Step 4, different rail underneath

## Architecture

```
┌─────────────────────┐
│  Pixel + StrongBox  │
│  ┌───────────────┐  │     1. tap Pay
│  │   Passkey     │◀─┼──── 2. biometric
│  │  (P-256 key   │  │     3. sign UserOp hash
│  │   in Titan)   │  │
│  └───────────────┘  │
└────────┬────────────┘
         │ UserOp + WebAuthn assertion
         ▼
┌─────────────────────┐     ┌──────────────────────┐
│   Pimlico bundler   │────▶│  ERC-4337 EntryPoint │
└─────────────────────┘     └──────────┬───────────┘
                                       │ validateUserOp
                                       ▼
                            ┌─────────────────────┐
                            │  Smart Wallet       │
                            │  (counterfactual    │
                            │   address from      │
                            │   passkey pubkey)   │
                            └──────────┬──────────┘
                                       │ holds USDC,
                                       │ verifies P-256 via WebAuthnSol
                                       │
                            ┌──────────▼──────────┐
                            │  USDC.transfer(...)  │
                            └─────────────────────┘
```

## Open architectural decisions

These need to be made before writing on-chain code:

### 1. Smart wallet implementation
Three candidates, none ideal:

| Wallet | Pros | Cons |
|---|---|---|
| **Coinbase Smart Wallet** | Battle-tested, deployed on Base Sepolia, supports WebAuthn via WebAuthnSol natively, official factory `0x0BA5ED0c6AA8c49038F819E587E2633c4A9F428a` | Coinbase-controlled upgrade path |
| **Safe (with passkey module)** | Most-audited multisig on EVM, broad ecosystem | Heavier, passkey support is via a module not native |
| **Custom 4337 wallet** | Full control | More attack surface, more time |

**Default:** Coinbase Smart Wallet. M3 description in PATH_A_NEXT.md
mentions it explicitly. Easiest to wire to Coinbase's hosted x402
facilitator.

### 2. x402 payment scheme
USDC's `transferWithAuthorization` uses `ecrecover` — it cannot be
called by a smart wallet directly because `ecrecover(hash, v, r, s) !=
smart_wallet_address`. Two paths around this:

**Path 2a — Smart wallet calls `USDC.transfer(...)` via UserOp:**
- Smart wallet itself moves USDC to facilitator's destination
- x402 server verifies the transaction hash matches the expected
  payment, no `transferWithAuthorization` involved
- Requires x402 server / facilitator that supports a
  "smart-account-direct" payment scheme (not standard `exact`)

**Path 2b — Smart wallet signs `transferWithAuthorization` via EIP-1271
delegation:**
- Doesn't actually work with current USDC (v2.2 still uses `ecrecover`)
- Would need USDC to support EIP-1271 in `transferWithAuthorization`,
  which it doesn't on Base Sepolia today

**Default:** Path 2a. Means we either need a custom x402 endpoint that
accepts "I made the transfer in tx X" or a facilitator that supports a
smart-account scheme.

### 3. Bundler / paymaster
- Pimlico API for bundling (free tier on testnet)
- For paymaster (gas sponsorship): Pimlico Verifying Paymaster on
  Base Sepolia, or the user pays ETH gas themselves
- Default: user pays ETH; revisit if UX needs gasless

### 4. x402 endpoint
The public `https://www.x402.org/protected` advertises only
`exact` scheme over Base Sepolia and Solana Devnet. To demo Path 2a
we need an endpoint that:
- Advertises a smart-account scheme in `accepts[]`, OR
- Accepts a "payment-by-tx-hash" envelope

**Default for first cut:** stand up our own mini Express endpoint that
accepts a UserOp hash + on-chain transaction reference as proof of
payment. ~30 lines of TS. Lives at `step5-android-passkey-x402/server/`.

## Milestones inside this step

Build incrementally, commit each:

### M2.1 — Counterfactual address derivation (no on-chain action)
- Compute the deterministic Coinbase Smart Wallet address from the
  passkey pubkey + factory + salt
- Display the address in the app
- Verify against a chain-state read: address has no code yet (it's
  counterfactual until the first UserOp deploys it)

### M2.2 — First UserOperation
- Build a UserOp that calls a noop or USDC.approve(...)
- Sign the UserOp hash with the passkey (WebAuthn assertion)
- Submit via Pimlico
- Verify deployment + execution on BaseScan
- This is the first time USDC need to be in the wallet

### M2.3 — USDC transfer via UserOp
- Same flow but executes `USDC.transfer(destination, amount)`
- Faucet wallet first
- Tx lands on BaseScan, USDC moves

### M3.1 — Custom x402 endpoint accepting smart-account proof
- Stand up `server/` with `@x402/express` configured for
  `exact-smart-account` scheme (or invent the protocol if no such
  scheme exists)
- Configure to verify by tx hash + signer = wallet address

### M3.2 — Wire Android client to the endpoint
- 402 challenge → parse → sign UserOp → submit via bundler →
  return tx hash to facilitator → 200 + protected page
- Same UX shape as Step 4 (one tap, one biometric)

### M4 — Polish + side-by-side demo with Step 4

## Files (planned)

```
step5-android-passkey-x402/
├── PLAN.md                                 (this file)
├── README.md                               (user-facing guide)
├── app/
│   ├── build.gradle.kts
│   └── src/main/java/app/x402spike/
│       ├── PasskeyWallet.kt                ← copied from step5
│       ├── P256Verify.kt                   ← copied from step5
│       ├── SmartWallet.kt                  ← NEW: counterfactual addr derivation
│       ├── UserOpBuilder.kt                ← NEW: build + hash UserOps
│       ├── PimlicoClient.kt                ← NEW: bundler RPC
│       ├── X402SmartClient.kt              ← NEW: x402 protocol with UserOp envelope
│       └── MainActivity.kt                 ← NEW: setup / fund / pay UI
├── server/                                 (M3.1)
│   ├── package.json
│   ├── server.ts                           ← @x402/express endpoint
│   └── README.md
├── wellknown-host/                         (deploy assetlinks for this app's package)
└── local.properties.example
```

## What we need from the user before M2.1

Nothing. M2.1 is pure address derivation, no API keys, no funding.

## What we need before M2.2

- Pimlico API key (free, https://dashboard.pimlico.io)
- Base Sepolia ETH for gas (the smart wallet's address needs ETH or a
  paymaster; first cut: user faucets ETH directly)

## What we need before M3.1

- Decision on whether to self-host a facilitator or modify an
  existing endpoint
- A deploy target for the endpoint (Render free tier / Vercel /
  Fly.io)
