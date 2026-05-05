# Step 5.4 — VC-bound x402 payments (deferred until Path A lands)

Captured 2026-05-04 after fetching the Verifiable Intent spec and the
AP2 repo to ground the architectural picture. Sits **after** Step 5
(Path A: passkey + smart wallet on Base) per the sequencing rule. Do
not start until Path A's milestones M1-M3 are committed.

## What this would be

The first publicly demonstrable end-to-end flow where:

1. An AI agent presents a **verifiable credential** (SD-JWT, ES256) proving
   it was authorized by the user to make a specific payment.
2. A merchant verifies the credential **off-chain** (standard JWT verification
   per RFC 9901).
3. The merchant issues a normal **x402 payment-required challenge**.
4. The wallet's **passkey in StrongBox** (Path A) signs the x402 envelope.
5. The payment **settles on Base Sepolia**, visible on BaseScan.

End result: a credential chain (issuer → user → agent → merchant) plus
visible on-chain settlement, all using P-256 throughout. As of 2026-05-04
no public demo of this exact flow exists.

## Why this is the right next step (after Path A)

Three early specs are converging on this point but nobody has glued them
to visible on-chain settlement yet:

- **Verifiable Intent** (`verifiableintent.dev/spec/`, v0.1-draft 2026-02-18):
  pure ES256 + SD-JWT credential layer. **Zero blockchain integration.**
- **AP2 (Agent Payments Protocol)** (`github.com/google-agentic-commerce/AP2`,
  v0.2): working Python/Go/Android agent demos. **Settlement is rail-
  agnostic — current samples use card flows, no chain references.**
- **x402** (this repo, Step 4): HTTP-level USDC payments on Base Sepolia.
  No credential layer, no agent flow.

Curves align cleanly:

| Layer | Curve |
|---|---|
| Verifiable Intent (ES256) | P-256 |
| WebAuthn / passkey | P-256 |
| StrongBox-supported EC | P-256 |
| Coinbase Smart Wallet via RIP-7212 | P-256 |

Every primitive in the stack speaks P-256. **The marginal complexity over
Path A is small** — just SD-JWT issuance/verification (a standard JWT
library handles it) plus an `extensions` field in the x402 envelope.

## Architecture, end-to-end

```
┌────────────────┐  L1: Issuer credential (SD-JWT)     ┌────────────────┐
│   Issuer       │ ────────────────────────────────-─▶ │     User       │
│   (e.g. bank)  │                                     │   wallet       │
└────────────────┘                                     │  (passkey      │
                                                       │   in StrongBox)│
                                                       └───────┬────────┘
                                                               │
                            L2: User → Agent delegation        │
                            (SD-JWT bound to agent's pubkey,   │
                             scoped: amount/merchant/window)   │
                                                               ▼
                                                       ┌────────────────┐
                                                       │     Agent      │
                                                       └───────┬────────┘
                                                               │
                       L3: Agent presents credential           │
                       to merchant alongside x402 envelope     │
                                                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│  Merchant (x402 server)                                              │
│                                                                      │
│  1. Receives request + SD-JWT in extensions                          │
│  2. Verifies SD-JWT signature chain (off-chain, standard JWT)        │
│  3. If valid: issues 402 with payment-required challenge             │
│     If invalid: 401 / 403                                            │
└──────────────────────────────┬───────────────────────────────────────┘
                               │
                               ▼
                    Wallet signs x402 envelope with
                    passkey (Path A flow, unchanged)
                               │
                               ▼
                    Coinbase Smart Wallet on Base Sepolia
                    settles transferWithAuthorization
                               │
                               ▼
                          BaseScan tx (visible)
```

## What to build

Four concrete pieces. Each is small.

### 1. SD-JWT issuance — `step5.4-vc/issuer.kt`
- Issuer key generated in software (or in another StrongBox alias)
- Issues an SD-JWT to the user's wallet at first launch
- Claims include: subject (user's passkey thumbprint via `cnf`), issuance
  timestamp, expiry, optional disclosures
- Spec reference: RFC 9901 SD-JWT, Verifiable Intent v0.1-draft for shape

### 2. SD-JWT presentation — extend `PasskeyWallet.kt` (Path A)
- Wallet stores the issued SD-JWT alongside the passkey credentialId
- On payment, builds an L2/L3 presentation (selective disclosure of just
  what the merchant needs to verify authorization)
- Includes the presentation in the x402 envelope's `extensions` field

### 3. SD-JWT verification — extend the x402 server / facilitator
- Demo server: a tiny Kotlin/Python service that:
  - Receives a request with `Authorization: SDJWT <token>` or in
    `extensions.verifiable_intent`
  - Verifies the JWT signature chain against the issuer's published JWKS
  - Checks claims (`exp`, `aud`, `cnf` matches the wallet's pubkey)
  - On success: returns 402 with a normal x402 challenge
  - On failure: returns 401 with a JSON error
- Spec reference: any standard SD-JWT library (Python: `sd-jwt`,
  Kotlin: `nimbus-jose-jwt` + manual disclosure handling)

### 4. UI / showcase wiring
- Status bar shows the credential's claims summary before the
  biometric prompt: "Agent X authorized to spend up to $50/day on
  groceries. This payment: $0.01."
- After settlement, BaseScan link is shown alongside a "Verify
  credential" button that re-runs the off-chain JWT verification with
  the captured token, proving the chain held throughout.

## Why this matters for digital-credential agentic commerce

This is the architectural shape digital-credential wallets like
[Multipaz](https://github.com/openwallet-foundation-labs/identity-credential)
are built for. Specifically:

- **mDL/EUDI Wallet credentials are SD-JWT (or mdoc).** The same wallet
  primitives identity wallets use today can carry payment authorizations.
- **Agentic commerce gateways need exactly this trust framework**: an
  agent making a payment must present cryptographic evidence of user
  authorization, scoped and revocable, verifiable by the merchant
  without contacting the user.
- **The on-chain settlement is the auditable receipt.** Everything else
  is off-chain plumbing; the BaseScan tx is the immutable evidence.

A working demo of this flow is high-leverage for:
- Architecture reviews of digital-credential + payments integrations
- Standards conversations (W3C WAWG, FIDO Alliance, IETF httpbis)
- Conference talks / blog posts demonstrating the end-to-end shape

## Risks and dependencies

1. **Path A must land first.** Without a working passkey-signed payment
   on Base Sepolia, this whole layer has nothing to settle on top of.
2. **AP2 is still v0.2.** The flow shape may change. Pin to a specific
   commit hash in `code/samples/python/scenarios/`.
3. **Verifiable Intent is still v0.1-draft.** The cnf binding shape is
   stable enough but the L1/L2/L3 chain semantics may evolve.
4. **No reference implementation of the bridge exists.** You're the
   integration point, not following someone's tutorial.
5. **Merchant verification of SD-JWT is non-trivial.** Standard JWT
   libraries don't do selective disclosure; you'll need an SD-JWT-
   specific lib or hand-roll the disclosure handling.

## What this is *not*

- Not on-chain credential verification (that would require a
  Solidity verifier for SD-JWT signatures — interesting but out of scope
  here; the merchant verifies off-chain, the chain only sees the
  resulting USDC tx).
- Not a Verifiable Credential 2.0 (W3C VC) implementation. SD-JWT is
  the Verifiable Intent spec choice; W3C VC 2.0 is a different shape.
  Could be added later as a parallel credential format.
- Not AP2 v1.0 conformant. It's an AP2-shaped demo using AP2's
  current samples as a harness, with x402/USDC swapped in for the
  card rail.

## Open questions to revisit when we resume

1. **Does AP2's Python sample expose a settlement plug-point?** If yes,
   we add x402 as a settlement adapter. If no, we fork the sample and
   replace the card-rail step. Check `code/samples/python/scenarios/`
   structure when we get there.
2. **Where does the SD-JWT live on the wire?** Options: (a) HTTP
   `Authorization: SDJWT <token>` header — clean and standard; (b)
   inside the x402 envelope's `extensions` field — keeps everything in
   one packet but increases envelope size.  Probably (a) is cleaner.
3. **How does the issuer key bootstrap?** For the spike, a static
   `issuer.json` JWKS file served via GitHub Pages or a Cloudflare
   Worker. For production, this is a real CA-shaped problem.
4. **Selective disclosure granularity?** SD-JWT lets the holder reveal
   only some claims to each verifier. For a payment, the merchant
   probably needs: subject pubkey, expiry, max amount, allowed merchant.
   Other claims (e.g., user identity) stay disclosed only to higher-
   trust parties.

## Pointers

- `PATH_A_NEXT.md` — required prerequisite (Path A milestones)
- `PATH_C_MPC.md` — alternative architecture, deferred
- `PATH_C_WIREFRAMES.md` — Path C UX sketches (some agent-management
  patterns may carry over to the VC presentation UX)
- Verifiable Intent spec: `verifiableintent.dev/spec/` (current 0.1-draft)
- AP2 repo: `github.com/google-agentic-commerce/AP2` (current v0.2,
  Python/Go/Android samples in `code/samples/`)
- SD-JWT spec: RFC 9901 (Selective Disclosure for JWTs)
- This is downstream of Step 5 (Path A); not a parallel track.
