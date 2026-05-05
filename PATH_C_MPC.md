# Path C — MPC / threshold signatures (deferred exploration)

Captured 2026-05-03 right after Step 4 landed. Path A (passkey + ERC-4337 +
RIP-7212) is still the next weekend per CLAUDE.md — this doc is *after* that.
The point of this note: when comparing architectures for the eventual
agentic-commerce product, Path A is not the only credible answer to "where
does the seed live so it isn't briefly in app RAM." MPC is the other one,
and the rest of the industry is genuinely split between them.

## The idea in one paragraph

The private key is never assembled. It exists only as `n` shards across
`n` parties (typically `n=2`: phone + backend). To sign, the parties run a
multi-round cryptographic protocol that produces a standard
ECDSA-secp256k1 signature without any party ever seeing the full key.
Output is byte-identical to a normal Ethereum signature — USDC's
`transferWithAuthorization` doesn't know or care it came from MPC.

This sidesteps the StrongBox curve problem entirely: you don't need
secp256k1 in hardware because you never have a single secp256k1 key in
software either.

## Why this is a peer of Path A, not a worse Path B

| | Path B (today) | Path A (next) | Path C (this doc) |
|---|---|---|---|
| Full secp256k1 seed exists? | Briefly, in RAM | Never (key is P-256) | Never (key is sharded) |
| RAM exposure window | ~1 ms per sign | None | None |
| Trust anchor | Pixel StrongBox | Pixel StrongBox | StrongBox **+** shard server |
| Backend infra you operate | None | None (or just a 4337 bundler) | At least one shard server, forever |
| On-chain footprint | EOA, vanilla USDC | Smart wallet, EIP-1271 | EOA, vanilla USDC |
| Recovery | BIP-39 phrase | Smart-wallet guardians + passkey sync | Shard reconstitution (no phrase) |
| Chain support | Any EVM | Chains with RIP-7212 / smart wallets | Any EVM (and beyond) |
| Policy enforcement choke point | None | Smart contract logic | Server shard refusing to co-sign |

The key tradeoff: Path A keeps the trust anchor entirely on the user's
device but pushes complexity onto the chain (smart wallet contract). Path
C keeps the chain interaction simple (vanilla EOA) but adds a long-lived
shard server you have to operate and secure.

For agentic commerce specifically, the shard server is sometimes a
**feature**: the server holding a shard is a natural place to enforce
policy ("agent X may spend up to $Y per day on category Z"), revoke
agent authority, or require a step-up auth before unusual actions. Path
A has to express the same policies as smart-contract code; Path C
expresses them as plain backend code. Different cost curves.

## Sketch of the implementation

If we built a `step6-mpc/` after `step5-passkey-smart-wallet/`, the
`payX402` call site stays identical to Step 4. Only the signer changes:

```kotlin
// Step 4 (today):
val sig = wallet.signWithSeed(activeId) { seed ->
    val pair = ECKeyPair.create(BigInteger(1, seed))
    Sign.signMessage(eip712Hash, pair, false)
}

// Step 6 (MPC):
val sig = mpcWallet.sign(activeId, eip712Hash)  // protocol round-trip happens inside
```

Inside `mpcWallet.sign`:

1. Phone holds shard A in StrongBox-backed `EncryptedSharedPreferences`.
2. Biometric prompt unlocks shard A for one operation.
3. Phone runs round 1 of the MPC protocol with shard A, sends commitment to backend.
4. Backend, which holds shard B in its HSM, runs round 1 reply.
5. Phone and backend exchange round 2 messages.
6. Phone produces a final ECDSA signature `(r, s, v)` — byte-identical to what
   a single-key ECDSA would have produced.
7. Envelope is sent to the x402 facilitator unchanged.

Key point: the facilitator sees a normal Ethereum signature. USDC sees a
normal `transferWithAuthorization`. The MPC ceremony is invisible
off-the-wire — it's an implementation detail of how the signature got
produced.

## Library / SDK candidates to evaluate

These exist today and could plausibly drop into a Step 6 weekend:

- **Coinbase WaaS** (Wallets-as-a-Service) — production, 2-of-2 MPC, Coinbase
  holds one shard. Closed source on the protocol side; SDK is fine. Mostly
  interesting as a benchmark for what "production MPC" looks like in practice.
  Probably too custodial-feeling for our purposes but worth understanding.
- **Web3Auth** (formerly Torus) — open-source MPC SDK, threshold scheme,
  tied to OAuth flows. Mature mobile SDKs (Android, iOS, web). Common
  choice for "social login → wallet" products.
- **Lit Protocol** — distributed key management on a permissionless network
  of nodes; MPC + access control. More speculative; the network model is
  novel and operationally heavy. Skim, don't adopt.
- **Privy / Dynamic / Turnkey** — embedded-wallet products built on MPC
  internally. Higher level than what we'd want for a learning spike, but
  good for understanding "what's the actual product surface."
- **ZenGo's two-party-ECDSA** (open-source, Rust + audits) — closer to what
  we'd actually implement if we wanted to *understand* the protocol. Lin et
  al. GG18 / CGGMP21 implementations. The "feel the rocks" version of MPC,
  same way Step 1 was the "feel the rocks" version of x402.

For a learning weekend, ZenGo's MP-ECDSA library + a tiny Kotlin/Node
backend that holds shard B is the right scope. For a product, Web3Auth or
Privy is the right scope.

## Decision criteria for Path A vs Path C in the actual product

When this folds back into Multipaz / agentic commerce, the question
"Path A or Path C" is not generic — it depends on three product choices:

1. **Who operates the wallet?**
   - User self-custody, no backend → Path A wins. No shard server, no
     custody ambiguity, no infrastructure.
   - Platform-provided wallet (we ship the agent, user lives inside it) →
     Path C is competitive. The shard server gives a natural surface for
     enforcing policy, providing recovery, and revoking compromised agents.

2. **What's the regulatory framing?**
   - "User owns the keys, we just provide UI" → Path A. Cleaner narrative
     for non-custodian status under most jurisdictions.
   - "We co-custody with the user" → Path C is honest about that. May
     trigger money-transmitter or VASP licensing depending on jurisdiction;
     legal needs to weigh in early.

3. **What recovery model do we want?**
   - "User has a passkey synced via iCloud / Google" → Path A.
   - "User can recover via email + step-up auth, no seed phrase ever" →
     Path C is structurally easier here.

The honest take: for **agentic commerce as a B2B product** (we sell agents
to merchants, agents pay on behalf of users with embedded wallets), Path
C is probably the more pragmatic answer. For **agentic commerce as a B2C
self-custody story** (Multipaz-native wallet, user owns the chip-bound
key, agents are advisory), Path A is the cleaner answer. We may want both,
behind a common signer interface.

## Scope of the eventual Step 6

If/when we get there:

1. Reuse Step 4's `SecureWallet` ABI but swap the implementation. Phone
   side stores a shard, not a seed. The `signWithSeed { }` lambda becomes
   `sign(hash) -> sig` — no seed ever touches the lambda.
2. Stand up a tiny Kotlin/Node backend on a free tier (Cloudflare Workers,
   Fly.io). Holds shard B in process memory or environment variables for
   the spike; production would be HSM-resident.
3. Wire the round-trip. Two HTTPS calls per signature (round 1, round 2).
   Latency budget: should be sub-second on Base Sepolia.
4. Pay the same x402 demo. Verify the resulting `(r, s, v)` lands on
   BaseScan and is indistinguishable from Step 4's signatures.
5. Compare the three architectures end-to-end: Step 4 (Path B), Step 5
   (Path A), Step 6 (Path C). Document the actual UX, latency, and
   operational footprint of each.

Not committing to any of this. Just want it written down so it's not
forgotten when Path A lands and we're choosing what's next.

## What this doc deliberately does not cover

- Specific MPC protocol math (GG18 vs CGGMP21 vs DKLs23) — read the papers
  if/when we actually build this.
- Operational hardening of the shard server (HSM choice, key rotation,
  geographic redundancy). That's a real engineering project, not a notes
  file.
- Regulatory analysis. Legal team's job, not ours.
- Cost modeling. Defer until we have actual product shape.
