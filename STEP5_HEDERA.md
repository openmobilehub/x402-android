# Step 5 (deferred) — Cross-chain x402: Hedera Testnet alongside Base Sepolia

Captured 2026-05-04. Step 5 follows Path A in the sequencing order, but its
prerequisites are mostly infrastructure (a facilitator) rather than crypto
(StrongBox already does what we need). Filing the plan here so we can pick
it up after Path A lands.

## Why this is interesting

The pitch of x402 + StrongBox is *one biometric tap, one signed envelope,
the network field decides the rail*. Demonstrating the same Pixel key
signing payments on **two distinct chains** — Base Sepolia and Hedera
Testnet — turns that pitch into something visible: change a dropdown,
fingerprint, USDC moves on a different ledger. Same wallet, different
rail. That's the AAIF / agentic commerce gateway story made real on a
phone.

## What does NOT need to change

- The StrongBox wrap key (AES-256-GCM, biometric-gated)
- `SecureWallet.signWithSeed { }` and the seed-zeroing pattern
- web3j's `StructuredDataEncoder.hashStructuredData()` — produces the
  same EIP-712 hash regardless of which chain ID you put in the domain
- `Sign.signMessage` (deterministic ECDSA via RFC 6979 — same code path)
- `Keys.toChecksumAddress` (EIP-55 is identical across EVM chains)
- The 402 → parse challenge → sign → retry HTTP envelope flow
- The "+ New StrongBox wallet" provisioning, the address derivation, the
  Wallets-tab management

The same Pixel address that pays on Base Sepolia *can* sign for Hedera —
both chains use secp256k1, both encode the address as the last 20 bytes
of `keccak256(pubkey)`, and Hedera's EVM compatibility layer accepts
EIP-712 signatures verbatim through its precompile.

> **Fork in the road (see `PATH_A_NEXT.md` → "The curve we don't use").**
> This plan deliberately takes the **secp256k1 / EVM-facade** path to reuse
> Step 4's exact signing code. That's a code-reuse win, but it inherits
> Path B's RAM-window weakness (the seed must enter app RAM to sign).
> Hedera *natively* supports **Ed25519**, which StrongBox can sign
> directly — a native-Ed25519 Hedera account would be hardware-backed end
> to end, with no smart wallet, no bundler, and no RAM window. The cost is
> losing the "identical envelope as Base" property and EVM tooling. Choose
> per goal: maximum code reuse (this doc) vs. maximum security/simplicity
> (native Ed25519 EOA).

## Open question that determines the whole approach

**Does Circle's testnet USDC on Hedera expose `transferWithAuthorization`
(EIP-3009)?**

- If **yes**: this is purely a facilitator + envelope plumbing story.
  Days of work, not weeks. The same `exact` scheme x402 v2 already uses
  on Base Sepolia works as-is.
- If **no** (HTS-only, no ERC-20 facade with EIP-3009): need a *custom
  x402 scheme* for Hedera — significant protocol work, possibly a
  pre-authorization model where the user pre-approves a facilitator
  contract to spend up to N USDC. Closer to a Permit2-style flow than
  EIP-3009.

**How to verify**: pull the testnet USDC contract address from
hashscan.io/testnet, call `function transferWithAuthorization` against
its EVM facade via Hashio's relay, see if it reverts with "function not
recognized" or accepts the call. 30 minutes of investigation.

Until that question is answered, the rest of this plan assumes the
optimistic case (EIP-3009 supported on Hedera USDC EVM facade).

## Hedera-specific prerequisites

Hedera differs from typical EVM L2s in one inconvenient way: an EVM
address `0x...` is just an **alias** until an underlying Hedera account
is created. Circle's faucet (and probably others) won't drip to a raw
alias that has no corresponding `0.0.xxxxx` account.

Two ways to bootstrap a StrongBox-derived address into a real Hedera
account:

1. **Send-from-an-existing-account trigger.** If the user has a Hedera
   Portal account funded with 1,000 test HBAR, sending a tiny HBAR
   amount (any amount, the sender pays the ~$0.05 in HBAR equivalent
   auto-account-create fee) to the StrongBox-derived `0x...` triggers
   auto-account-create on Hedera. After that one-time bootstrap, the
   alias is a "real" account and Circle's faucet, HashScan, etc. all
   recognize it.

2. **Self-transfer signed by the StrongBox key, relayed via Hashio.**
   Cleaner architecturally but requires the Hashio relay to pay (or
   waive) the auto-create fee. Spotty in practice; some relays do this
   for first-time aliases on testnet, others don't.

For the demo, **#1 is the right path**: include a one-shot "Bootstrap on
Hedera" button in the app's Wallets tab, surfaced only when the active
wallet has no Hedera account yet, that:

- Asks the user to paste their Portal-account `0.0.xxxx` ID and the HEX
  private key once (saved to `local.properties`, not StrongBox — it's
  the portal's key, not ours)
- Submits a 1 HBAR transfer to the active StrongBox `0x...` via the
  Hashio JSON-RPC, signed by the portal key
- Confirms via HashScan API that the account now exists
- Stores a per-wallet flag so the bootstrap only runs once per wallet

That's a one-line opt-in in `local.properties` and a small new screen
in the Wallets tab. Maybe 100 lines.

## Code changes (assuming Hedera USDC has EIP-3009)

### `X402Client.kt`
Currently hardcodes `NETWORK = "eip155:84532"` and `CHAIN_ID = 84532L`.
Change to derive both from the challenge's selected `accepts[]` entry:

```kotlin
val networkCaip = accept["network"]!!.jsonPrimitive.content     // "eip155:296"
val chainId = networkCaip.substringAfter("eip155:").toLong()    // 296
```

Pass both into `signTransferWithAuthorization` and the envelope builder.
~15 lines.

### Network selection UI (Pay tab)
A small chip group above the Pay button: `Base Sepolia` / `Hedera Testnet`.
Selecting one filters the `accepts[]` matcher to that network only. If
the demo endpoint doesn't advertise the chosen network, show a clear
"this endpoint doesn't accept Hedera" message rather than silently
falling back. ~40 lines.

### Demo endpoint
The public `https://www.x402.org/protected` advertises only Base Sepolia
and Solana Devnet. To demo Hedera we need a different endpoint that
advertises Hedera Testnet in its `accepts[]`. Either:

a) Stand up our own with `@x402/express` (~30 lines of TS), deploy
   somewhere reachable (Render free tier, Cloudflare Workers, Fly.io).
   Configure it with both Base Sepolia and Hedera Testnet accepts.

b) Find a community-run multi-chain x402 endpoint that already
   advertises Hedera. Unlikely to exist as of now.

(a) is the right move. The endpoint code is small but it has to point
at a facilitator (next item) that can settle on Hedera.

### Facilitator on Hedera (the actual lift)

Coinbase's hosted facilitator settles only on the chains it supports
(Base, OP, Arbitrum, etc.). It does **not** settle on Hedera. So we
either:

a) **Self-host a facilitator** for Hedera using `@x402/facilitator`
   from the x402-foundation monorepo. Setup:
   - Fund a Hedera testnet wallet with HBAR (gas) and USDC (settlement)
   - Configure `@x402/facilitator` with Hashio's RPC URL and that wallet
   - Deploy: Render free tier, Cloudflare Workers, or local + ngrok for
     a demo
   - Estimated 200–400 lines of config + deployment scaffold

b) **Wait for the public Coinbase facilitator to add Hedera.** Out of
   our control.

(a) is the path. This is the bulk of Step 5 effort, not the Android
side.

## File plan

| File | Lines | Purpose |
| --- | --- | --- |
| `step4-android-strongbox/app/.../X402Client.kt` | ~15 modified | Per-call network/chainId from challenge |
| `step4-android-strongbox/app/.../MainActivity.kt` | ~40 added | Network selector chip group on Pay tab |
| `step4-android-strongbox/app/.../HederaBootstrap.kt` | ~120 new | One-shot auto-account-create via portal key |
| `step4-android-strongbox/local.properties.example` | +6 | `HEDERA_PORTAL_ACCOUNT_ID`, `HEDERA_PORTAL_HEX_KEY` (testnet only, never commit) |
| `step5-facilitator/` (new module, TS) | ~200 | `@x402/facilitator` config + deploy scripts |
| `step5-facilitator/server.ts` | ~30 | `@x402/express` resource server advertising both chains |

App-side total: ~175 lines. Facilitator side: ~230 lines + a deploy
target.

## UX flow on the device

1. User opens the app. Active StrongBox wallet shows on the Pay tab.
2. Network chip group reads `Base Sepolia | Hedera Testnet`. Default
   selection is whatever was last used.
3. If user selects Hedera and the active wallet has never been
   bootstrapped on Hedera (no `0.0.xxxxx` mapping cached in prefs),
   show a one-time "Bootstrap on Hedera" prompt with a Snackbar action.
4. Tap Pay → biometric → seed unwraps → EIP-712 signed with chainId 296
   → envelope POSTs to our demo endpoint → endpoint forwards to our
   self-hosted Hedera facilitator → facilitator submits
   `transferWithAuthorization` to Hedera testnet USDC → ~5 second
   settlement → app shows the HashScan URL instead of BaseScan.

Same biometric, same StrongBox-bound key, different rail.

## Why this is Step 5, not Step 4.5

CLAUDE.md's sequencing is: 1 → 2 → 3 → 4 → Path A. This Hedera work
sits *after* Path A because:

- Path A removes the seed-in-RAM window that's the main weakness of
  Step 4. Solving "same key, multiple chains" on top of an already-
  exposed seed is less impressive than solving it on top of a fully
  non-extractable passkey.
- The cross-chain story is more compelling once we've also moved off
  the public Coinbase facilitator (Step 4.1 / CDP integration), which
  proves we can run our own facilitator infrastructure.
- The Path A WebAuthn signature is easier to verify on a custom
  facilitator (P-256 precompile is standardized via EIP-7951) than
  bridging secp256k1 envelopes across two chains.

So: ship Path A first. Then this becomes a clean extension demo.

## Estimated effort

- **App side**: half a day if Hedera USDC supports EIP-3009. Two days
  if we have to design a custom scheme.
- **Facilitator + deployment**: one to two days, depending on how
  polished the deploy story needs to be.
- **Demo endpoint**: half a day.
- **Total realistic**: 3–4 days post-Path A, mostly infra not crypto.

If the EIP-3009 question on Hedera USDC comes back negative, double
that and treat it as a separate research spike before scoping the
build.
