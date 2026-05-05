# Path A — what to do when we resume

Captured 2026-05-03 right after Step 4 (StrongBox-wrapped seed) landed and
verified `STRONGBOX` on Pixel 10 Pro. Everything below is pre-decided so
we can pick up cold.

## Where we are

- ✅ Step 1 (Node), Step 2 (Kotlin CLI), Step 3 (Android hardcoded), Step 4
  (Android StrongBox-wrapped seed) — all committed, all paid the x402
  demo on Base Sepolia.
- ✅ `KeyInfo.getSecurityLevel()` logs `STRONGBOX` on Pixel 10 Pro / Titan M2.
- ✅ Path B is the "feel the rocks" version. Identified weakness: 1-ms
  RAM window for the secp256k1 seed during signing. Inherent to the
  curve mismatch — StrongBox doesn't support secp256k1.

## Goal of Path A

Eliminate the RAM window. The signing key (a P-256 passkey) lives in
StrongBox forever, signs in StrongBox, never appears in app RAM. USDC
verification stays on-chain via a smart wallet contract that accepts
WebAuthn assertions through the RIP-7212 precompile on Base.

Same `payX402` UX as Step 4. Different signer implementation. Different
on-chain identity (smart wallet contract instead of EOA).

## Milestone 1 — passkey hello world (the very next thing)

Scope: ~200 lines, ~1 Saturday morning if Credential Manager cooperates.

1. Scaffold `step5-android-passkey/` mirroring Step 4's directory shape.
2. Add `androidx.credentials:credentials:<latest>` to `app/build.gradle.kts`.
3. Set up Digital Asset Links so the passkey can bind to the app:
   `app/src/main/res/values/strings.xml` + a `.well-known/assetlinks.json`
   served at the RP-ID origin (use a localhost ngrok or a static gist for
   the spike).
4. Replace `SecureWallet.kt` with `PasskeyWallet.kt`:
   - `createPasskey()`: `CreatePublicKeyCredentialRequest`, parse the
     attestation, extract the P-256 public key, store the credentialId.
   - `signChallenge(hash: ByteArray)`: `GetCredentialRequest` for that
     credentialId, return the assertion (`r, s, authenticatorData,
     clientDataJSON`).
   - `describeSecurityLevel()`: read `KeyInfo` for the underlying key,
     log `STRONGBOX` (or whatever — flag silent fallback per CLAUDE.md).
5. Strip the activity to two buttons:
   - **Create passkey** — calls `createPasskey()`, shows the public key.
   - **Sign test challenge** — signs `keccak256("hello world")`, prints the
     assertion, runs an off-chain P-256 verify in Kotlin to confirm the
     signature is valid.
6. Run on Pixel. Confirm `STRONGBOX` log line. Commit as `Step 5.0`.

Done criterion: passkey creation + signing both fire the system passkey
UI, both produce valid P-256 signatures, security level logs `STRONGBOX`,
no on-chain involvement yet.

## Subsequent milestones (do not start until M1 is committed)

- **M2 — Counterfactual Coinbase Smart Wallet on Base Sepolia** (~1 weekend)
  Compute the smart wallet's deterministic address from the passkey
  pubkey + factory + salt. Faucet USDC to it. Build a UserOperation that
  calls `transferWithAuthorization`, signed by the passkey. Submit via
  Pimlico bundler. Land on BaseScan.

- **M3 — Wire through x402 (EIP-1271 path)** (~½ weekend)
  Use the smart wallet's `isValidSignature` callback so the existing CDP
  facilitator can submit the tx like a normal EOA payment. Same x402
  envelope shape as Step 4.

- **M4 — UX polish + side-by-side with Step 4** (~½ weekend)
  Status bar shows which security level was used. Compare flows visibly.
  Demo recording.

## Recovery model — multiple owners, not single passkey

The legitimate concern: the passkey lives in StrongBox; if the device is
lost or destroyed, the key is gone. Without explicit recovery design,
that means lost device = lost wallet = lost USDC. **Don't ship Path A
without addressing this.**

The architectural answer is that Coinbase Smart Wallet (and Safe with
the passkey module, and most ERC-4337 wallets) supports **multiple
owner keys natively**. Any one of them can sign; losing one doesn't
brick the wallet. The recovery setup happens once at wallet creation.

### The defense-in-depth stack

| Layer | What it covers | Cost to user |
|---|---|---|
| **Passkey sync via Google Password Manager** | 95% of cases — lost or broken phone, sign into a new device with the same Google account, passkey reappears in StrongBox/Secure Enclave on the new device. End-to-end encrypted by Google. | Zero — automatic if the wallet declares the passkey as syncable |
| **Multiple passkeys on multiple devices** | Google account compromise, sync failure, multi-device convenience. Add a passkey from a laptop, tablet, or second phone at wallet creation; all are equivalent owners. | One extra setup step per device |
| **Hardware key (YubiKey) as offline backup** | Catastrophic case (cloud breach + lost devices simultaneously). Register a YubiKey as a third owner, keep it offline. | One setup ritual + safe storage |
| **Optional: social-recovery via Safe Recovery module** | Trustless recovery without a hardware key. M-of-N guardians with a time-locked delay. | Heavier UX; only for users who specifically want trustless recovery |

For a learning-spike Path A demo, **Google Password Manager sync alone**
is enough — same recovery experience as logging into a new phone with
your Google account. For anything closer to production, layer in
multi-owner.

### Smart wallet API for multi-owner

Coinbase Smart Wallet (Solidity, simplified):

```solidity
mapping(address => bool) public isOwner;

function addOwnerAddress(address newOwner) external onlyOwner;
function removeOwnerAddress(address oldOwner) external onlyOwner;
function isValidSignature(bytes32 hash, bytes signature) returns (bytes4);
```

Adding a backup key is one transaction signed by an existing owner. The
smart wallet contract enforces no upper bound on owner count (within
gas-cost reason) and `isValidSignature` accepts a signature from any
registered owner.

### Concrete UX plan for the wallet

The wallet creation flow should land in this order:

1. **Create the primary passkey** in StrongBox on the Pixel (daily-driver)
2. **Enable Google Password Manager sync** (default; just an entitlement)
3. **First-launch prompt: "Add a backup key"** — walks the user through
   adding a passkey from a second device or a YubiKey. Strongly
   encouraged but skippable; a "skip for now" path is honest about the
   tradeoff
4. **Wallets tab surfaces the owner list** — "this wallet has N keys:
   Pixel, MacBook, YubiKey" — turning "in theory you can recover" into
   "in practice you will because you can see your recovery state"

Step 4 is what makes this real. Without surfacing the owner list,
users don't know whether they have one key or three; with it, the
backup-key prompt becomes a habit.

### Why this is *better* than BIP-39, not worse

| | BIP-39 mnemonic | Passkey + smart wallet (multi-owner) |
|---|---|---|
| Lose the phrase / device | Total irrecoverable loss | Multiple recovery paths |
| Phrase exposed to a phishing site | Total drain possible | Phishing-resistant by origin binding |
| Sync across devices | Manual, dangerous | Automatic, end-to-end encrypted |
| Replace a compromised key | Move funds to a new wallet (visible on-chain, attackable) | `removeOwnerAddress` + `addOwnerAddress`, atomic |
| Recovery UX | "Find the piece of paper from 18 months ago" | "Sign into Google account on new phone" |

The "lose the device, lose everything" failure mode only exists if the
wallet is built with a *single* passkey owner. Multi-owner is the
production-grade default for ERC-4337 wallets and what Path A should
adopt from M2 onward.

### Action item for M2

When building the smart wallet integration in M2, **make
`addOwnerAddress` part of the post-creation flow**, not an advanced
setting. The Wallets tab should refuse to show "wallet ready" until
either the user has confirmed Google Password Manager sync is enabled
*or* they've added a second owner key.

## Simplified variant if a weekend is all we have

Skip ERC-4337 entirely. Use the Coinbase Smart Wallet contract directly
via `eth_sendRawTransaction` from a relayer key (Step 4's EOA, used as
gas funder). The smart wallet still verifies the WebAuthn assertion and
moves the USDC. Loses gasless UX; keeps the security claim.

This collapses M2 + M3 into ~1 weekend. Add 4337 in a Step 5.1 if it
matters.

## Decisions deferred until we resume

1. **Which smart wallet?** Coinbase Smart Wallet (default — best mobile
   reference) vs Safe with passkey module (more flexible) vs Argent
   (smaller ecosystem). Default to Coinbase unless something pushes back.
2. **Bundler?** Pimlico Base-Sepolia free tier is the default. Stackup or
   Alchemy are alternatives. Decide at M2.
3. **RP-ID hosting?** Need to serve a `.well-known/assetlinks.json` at
   some origin to bind the passkey to the app. ngrok for the spike;
   GitHub Pages or a Cloudflare Worker for anything more permanent.
4. **Which backup keys to add at wallet creation?** Recovery model is
   defined (see "Recovery model" section above). The remaining decision
   is *which* specific backup paths to require vs. recommend at
   creation time — minimum: Google Password Manager sync. Stronger:
   prompt for a second device passkey or YubiKey before "wallet ready"
   state. Decide based on target user (learning spike vs. wallet-as-
   product).

## Risk-watch (where the schedule actually goes)

- **Encoding the WebAuthn assertion for `isValidSignature`** — half a
  day budget. Reference is Coinbase's TypeScript SDK; we port concepts.
- **First-deploy gas estimation** — bundler simulates against a contract
  that doesn't exist yet. Pimlico's docs are decent; budget a few hours.
- **Android Credential Manager quirks** — half a day. Most likely:
  attestation chain quirks, assertion-format differences, or RP-ID
  binding errors.

## Pointers

- Step 4 reference for the `*Wallet` class shape:
  `step4-android-strongbox/app/src/main/java/app/x402spike/SecureWallet.kt`
- The "what's the same, what's different" between Step 4 and Path A is
  in the conversation transcripts. Tightest summary: same hardware
  (StrongBox), different curve (P-256 vs secp256k1), different chain-side
  verification (smart wallet `isValidSignature` vs EOA `ecrecover`).
- Path C exploration (deferred indefinitely): `PATH_C_MPC.md` +
  `PATH_C_WIREFRAMES.md`. Not committed, not pursuing for now.

## Reminder for future-me

- One step at a time. Do not collapse M1 + M2 in one PR.
- Log security level loudly. Silent TEE fallback is the bug we'd never see.
- This is a learning spike, not a production thing. The simplified variant
  is fine if a weekend is all we have.
