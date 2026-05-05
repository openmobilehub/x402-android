# Path C — UX wireframes (deferred exploration)

Captured 2026-05-03. Companion to `PATH_C_MPC.md`. Information-architecture
sketches, not visual designs — they focus on the UX moments where Path C
is *meaningfully different* from Path B (Step 4) and Path A.

The four moments worth wireframing:

1. **Onboarding** — establish the dual-shard mental model
2. **Pay flow** — make the two-actor signing visible (it's slower than Path
   A; users notice if you don't explain why)
3. **Recovery on a new device** — the no-seed-phrase move; this is where
   Path C earns its keep for non-crypto-native users
4. **Agent management** — the agentic-commerce showcase; doesn't exist in
   Paths A or B

---

## Screen 1 — Onboarding (first launch)

```
┌─────────────────────────────────┐
│  ◀  Set up your wallet          │
├─────────────────────────────────┤
│                                 │
│            🔐                   │
│             +                   │
│            ☁️                   │
│                                 │
│   Two-key setup                 │
│   ───────────────               │
│   • This device (your finger)   │
│   • Recovery service (sign-in)  │
│                                 │
│   Both must agree to spend      │
│   from this wallet.             │
│                                 │
│   No seed phrase to lose.       │
│   Recover on any device with    │
│   email + biometric.            │
│                                 │
│   [ Continue                  ] │
│                                 │
│   By continuing you agree...    │
└─────────────────────────────────┘
```

**Why this screen exists:** the "two keys, neither alone is enough" mental
model is non-obvious. If users don't understand it, they'll be confused
later when a payment shows two progress steps. Front-load the model in 30
words.

---

```
┌─────────────────────────────────┐
│   Linking to recovery service   │
├─────────────────────────────────┤
│                                 │
│   ✓ Device shard generated      │
│   ⏳ Recovery shard generated   │
│   ⏳ Address derived            │
│                                 │
│   ████████████░░░░░░░░  60%     │
│                                 │
│       [ touch sensor       ]    │
│       [ to authorize       ]    │
│       [ this device shard  ]    │
│                                 │
│                                 │
│   This takes ~3 seconds. Your   │
│   shard never leaves the chip.  │
│                                 │
└─────────────────────────────────┘
```

**What's happening under the hood:** distributed key generation (DKG)
round-trip with the backend. Phone produces shard A, backend produces
shard B, neither party sees the other's. The address is derived
deterministically from the public output. Biometric prompt fires *during*
DKG so shard A is StrongBox-encrypted before it lands on disk.

---

```
┌─────────────────────────────────┐
│  Wallet ready                   │
├─────────────────────────────────┤
│                                 │
│   0x209693Bc6afc...EF312287C    │
│   [ Copy ] [ BaseScan ]         │
│                                 │
│   Security: STRONGBOX  +  MPC   │
│   ▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔   │
│                                 │
│   ✓ Device shard in Titan M2    │
│   ✓ Recovery shard in HSM       │
│   ✓ No seed phrase exists       │
│                                 │
│   Balance: 0 USDC               │
│   [ Recharge ]                  │
│                                 │
│   [ ▶ Pay 0.01 USDC           ] │
│                                 │
└─────────────────────────────────┘
```

**Distinctive surface:** the badge "STRONGBOX + MPC" is the security claim
users see. It's load-bearing for the showcase. Same instinct as Step 4's
`last seen wrap-key level: STRONGBOX`, extended to two trust anchors.

---

## Screen 2 — Pay flow

The two-actor signing has to be visible. If you make it look like a
single biometric tap (Path B/A flow), users will be confused when payments
take 1.5–2s instead of 200ms. Make the wait *legible*.

```
┌─────────────────────────────────┐
│  Pay 0.01 USDC                  │
├─────────────────────────────────┤
│                                 │
│   To: x402.org/protected        │
│   Amount: 0.01 USDC             │
│                                 │
│                                 │
│   ⏳ Authorizing                │
│                                 │
│   ✓ Hash computed               │
│   ⏳ Your device                │
│   ⏳ Recovery service           │
│   ⏳ Settlement                 │
│                                 │
│   ████░░░░░░░░░░░░░░░░  20%     │
│                                 │
│       [ touch sensor       ]    │
│                                 │
└─────────────────────────────────┘
```

After biometric:

```
┌─────────────────────────────────┐
│  Pay 0.01 USDC                  │
├─────────────────────────────────┤
│                                 │
│   ⏳ Co-signing with recovery   │
│      service…                   │
│                                 │
│   ✓ Hash computed               │
│   ✓ Your device                 │
│   ⏳ Recovery service           │
│   ⏳ Settlement                 │
│                                 │
│   ████████████░░░░░░░░  60%     │
│                                 │
│   The recovery service can      │
│   refuse if this violates a     │
│   spending limit.               │
│                                 │
└─────────────────────────────────┘
```

If the server **refuses** (this is the unique-to-Path-C failure mode):

```
┌─────────────────────────────────┐
│  ⚠  Payment blocked             │
├─────────────────────────────────┤
│                                 │
│   The recovery service          │
│   declined to co-sign.          │
│                                 │
│   Reason:                       │
│   "Daily limit exceeded for     │
│    Shopping Assistant agent."   │
│                                 │
│   [ Increase limit ]            │
│   [ Sign in to override ]       │
│   [ Cancel ]                    │
│                                 │
│   No tokens were spent.         │
│                                 │
└─────────────────────────────────┘
```

**Why surface this:** Path C's superpower vs. Path A is that the server is
a policy choke point. If the user can't see *that* it refused and *why*,
the choke point is invisible — and arguably useless. Make the refusal a
real, well-designed UI moment.

Success state on the home screen, after settlement:

```
┌─────────────────────────────────┐
│  ✅ Paid                         │
├─────────────────────────────────┤
│                                 │
│   0.01 USDC sent                │
│   Tx: 0xabc…f29  →  BaseScan    │
│                                 │
│   Signed by:                    │
│   • Your fingerprint            │
│   • Recovery service            │
│                                 │
│   On-chain: indistinguishable   │
│   from a normal Ethereum tx.    │
│                                 │
└─────────────────────────────────┘
```

---

## Screen 3 — Recovery on a new device

This is where Path C's UX pays off. New phone, no seed phrase, no setup
envelope to find.

```
┌─────────────────────────────────┐
│  Welcome back                   │
├─────────────────────────────────┤
│                                 │
│   Restoring your wallet         │
│                                 │
│         📧 + 👆                 │
│                                 │
│   Step 1: Sign in to your       │
│   recovery account.             │
│                                 │
│   [ Sign in with Google ]       │
│   [ Email + magic link  ]       │
│                                 │
│                                 │
│                                 │
│   No seed phrase needed. The    │
│   recovery service holds half   │
│   of your wallet's signing      │
│   power and will help you       │
│   migrate to this device.       │
└─────────────────────────────────┘
```

After OAuth + step-up:

```
┌─────────────────────────────────┐
│  Migrating to this device       │
├─────────────────────────────────┤
│                                 │
│   ✓ Identity confirmed          │
│   ✓ Step-up auth passed         │
│   ⏳ Generating new device      │
│      shard                      │
│                                 │
│   ████████░░░░░░░░░░░  40%      │
│                                 │
│       [ touch sensor       ]    │
│       [ on this device     ]    │
│                                 │
│                                 │
│   Your old device's shard       │
│   will be revoked. Any agents   │
│   bound to that device will     │
│   need re-authorization.        │
│                                 │
└─────────────────────────────────┘
```

Done — new device has its own shard, old device's shard revoked, wallet
address unchanged. The user *never* sees a 12-word phrase.

**Why this works:** in Path C the address is derived from the joint
public output of DKG, which is stable. The phone shard can be regenerated
freely as long as the backend agrees to participate in re-sharding. Users
get a wallet recovery experience that feels like "log in to my account on
a new phone" — the universal mobile UX — instead of "find the piece of
paper from 18 months ago."

---

## Screen 4 — Agent management (Path C's killer feature for agentic commerce)

This screen does not exist in Path A or Path B. It's the differentiator.

```
┌─────────────────────────────────┐
│  Authorized agents              │
├─────────────────────────────────┤
│                                 │
│  ╭─────────────────────────────╮│
│  │ 🛒 Shopping Assistant      ✓ ││
│  │ Limit: $50/day              ││
│  │ Categories: groceries,      ││
│  │             household       ││
│  │ Last used: 2h ago           ││
│  │ [ Edit ] [ Pause ] [ Revoke]││
│  ╰─────────────────────────────╯│
│                                 │
│  ╭─────────────────────────────╮│
│  │ ✈  Travel Booker           ✓ ││
│  │ Limit: $500/trip            ││
│  │ Categories: travel          ││
│  │ Last used: 3d ago           ││
│  │ [ Edit ] [ Pause ] [ Revoke]││
│  ╰─────────────────────────────╯│
│                                 │
│  [ + Add agent              ]   │
│                                 │
│  Agents pay through your        │
│  wallet. The recovery service   │
│  enforces these limits.         │
└─────────────────────────────────┘
```

When an agent attempts to spend (the user gets a pre-settlement surface):

```
┌─────────────────────────────────┐
│  Agent payment                  │
├─────────────────────────────────┤
│                                 │
│   🛒 Shopping Assistant         │
│      wants to pay               │
│                                 │
│   $12.50 → Whole Foods          │
│                                 │
│   Within your policy:           │
│   ✓ Daily limit ($37.50 left)   │
│   ✓ Allowed category            │
│                                 │
│   Auto-approving in 5s…         │
│                                 │
│   [ Approve now ]               │
│   [ Block this payment ]        │
│   [ Block & revoke agent ]      │
│                                 │
└─────────────────────────────────┘
```

**Why this is the showcase screen:** Path A can express agent permissions
only via smart-contract logic, which is rigid (you'd update on-chain to
change a limit) and gas-expensive. Path C expresses them as plain backend
policy rules — adjustable from a settings screen, no on-chain changes, no
gas cost. The agentic-commerce gateway product is *largely* this screen,
plus the policy engine behind it. Path B/A would have to invent a
different surface to get equivalent functionality.

---

## What's deliberately *the same* as Step 4 / Path A

- The **Pay** button placement and the BaseScan link follow Step 4's
  pattern.
- The **STRONGBOX** badge on the home screen is the same instinct as Step
  4's `last seen wrap-key level: STRONGBOX` — make the security claim
  visible.
- The **Recharge** flow is identical (faucet.circle.com link, address
  copy).
- The on-chain tx, when settled, is byte-identical to a Step 4 tx — same
  envelope, same `transferWithAuthorization`. Users could even alternate
  between Path B and Path C on the same address (in theory) and see no
  difference on BaseScan.

## What's deliberately *different*

- **Onboarding shows two trust anchors**, not one.
- **Pay flow shows a 4-step progress**, not a single biometric tap.
- **Refusals are first-class UI**, not error toasts.
- **Recovery uses email + step-up**, not seed phrases.
- **Agent management exists at all.**

## What I'd build first if these became real screens

If you ever want to prototype these in actual code (Compose preview /
Figma / even Streamlit), the highest-leverage one is **Screen 4 (agent
management)** — it's the one that distinguishes Path C from everything
else and the one that maps directly to Multipaz's agentic-commerce
thesis. Screens 1–3 are variations on a theme; screen 4 is the actual
product.
