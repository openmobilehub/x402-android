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

The flow uses the **W3C Digital Credentials API** (with the **OID4VP
`transaction_data` extension**) so the credential presentation and the
x402 payment authorization can be bundled into a single wallet
interaction with **one biometric prompt**. Two distinct signatures
emerge from one user-verification event.

```
┌────────────────┐  L1: Issuer SD-JWT      ┌────────────────┐
│   Issuer       │ ──────────────────────▶ │     User       │
│   (e.g. bank)  │                         │   wallet       │
└────────────────┘                         │  (passkey      │
                                           │   in StrongBox)│
                                           └───────┬────────┘
                                                   │
              L2: User → Agent delegation          │
              (SD-JWT bound to agent's pubkey)     │
                                                   ▼
                                           ┌────────────────┐
                                           │     Agent      │
                                           └───────┬────────┘
                                                   │ HTTP GET /protected
                                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│  Merchant (x402 server)                                              │
│                                                                      │
│  Returns 402 with a single DC API request bundling:                  │
│    • presentation_definition  — VI L3 credential required            │
│    • transaction_data         — x402 payment authorization template  │
│                                 (amount, asset, payTo, EIP-712 hash) │
└──────────────────────────────┬───────────────────────────────────────┘
                               │
                               ▼
              Wallet renders ONE consent screen:
              ┌────────────────────────────────────────┐
              │  x402.org is requesting:               │
              │                                        │
              │  📋 Credential                         │
              │     • Spending authority (up to $50)   │
              │     • Category: groceries              │
              │                                        │
              │  💳 Payment                            │
              │     0.01 USDC  →  0x209693…287C        │
              │     Base Sepolia                       │
              │                                        │
              │       [ Touch sensor to approve ]      │
              └───────────────────┬────────────────────┘
                                  │
                                  ▼  ONE biometric
                                  │
              Wallet produces atomically (under one user-verification):
                  • KB-JWT signed by passkey
                    (commits to sd_hash + transaction_data_hashes)
                  • EIP-712 signature over x402 authorization
                    (same passkey, same biometric event)
                                  │
                                  ▼
              Returns: vp_token + signed x402 envelope
                                  │
                                  ▼
┌──────────────────────────────────────────────────────────────────────┐
│  Merchant verifies (off-chain)                                       │
│  1. SD-JWT chain    — issuer signatures across L1/L2/L3              │
│  2. KB-JWT          — holder controls L3 cnf key                     │
│  3. transaction_data_hashes match the bundled x402 payment           │
│  4. EIP-712 signature  — P-256 ECDSA against the same passkey        │
│  5. Forwards x402 envelope to facilitator                            │
└──────────────────────────────┬───────────────────────────────────────┘
                               │
                               ▼
              Coinbase Smart Wallet on Base Sepolia
              settles transferWithAuthorization
              (P-256 verification on-chain via RIP-7212)
                               │
                               ▼
                          BaseScan tx (visible)
```

## Wire format

Concrete JSON shapes for the merchant ↔ wallet exchange. Builds on
existing standards: OID4VP for the credential request, OID4VP
`transaction_data` for the bundled payment ask, SD-JWT KB-JWT with
`transaction_data_hashes` for the cryptographic commitment, and the
x402 v2 envelope shape (unchanged from Step 4) for chain settlement.

### Phase 1 — Merchant request (in the 402 response body)

```json
{
  "client_id": "https://x402.org",
  "nonce": "random-server-challenge-7f3a2b1c",
  "response_mode": "direct_post",

  "presentation_definition": {
    "id": "vi-payment-auth",
    "input_descriptors": [
      {
        "id": "verifiable_intent_l3",
        "format": { "vc+sd-jwt": { "alg": ["ES256"] } },
        "constraints": {
          "fields": [
            { "path": ["$.iss"],
              "filter": { "const": "did:web:trusted-issuer.example" } },
            { "path": ["$.scope.merchant"],
              "filter": { "const": "x402.org/protected" } },
            { "path": ["$.scope.amount.max"],
              "filter": { "type": "string", "pattern": "^[0-9]+$" } }
          ]
        }
      }
    ]
  },

  "transaction_data": [
    {
      "type": "x402_payment_v2",
      "credential_ids": ["verifiable_intent_l3"],
      "transaction_data_hashes_alg": "sha-256",

      "payment": {
        "scheme": "exact",
        "network": "eip155:84532",
        "asset": "0x036CbD53842c5426634e7929541eC2318f3dCF7e",
        "amount": "10000",
        "payTo": "0x209693Bc6afc0C5328bA36FaF03C514EF312287C",
        "maxTimeoutSeconds": 300,
        "extra": { "name": "USDC", "version": "2" }
      },

      "eip712_authorization_template": {
        "from":        "<wallet fills its address>",
        "to":          "0x209693Bc6afc0C5328bA36FaF03C514EF312287C",
        "value":       "10000",
        "validAfter":  "<wallet fills>",
        "validBefore": "<wallet fills>",
        "nonce":       "<wallet fills 32 random bytes>"
      }
    }
  ]
}
```

### Phase 2 — Wallet response (after one biometric)

```json
{
  "presentation_submission": {
    "id": "submission-9b8c7d6e",
    "definition_id": "vi-payment-auth",
    "descriptor_map": [
      { "id": "verifiable_intent_l3",
        "format": "vc+sd-jwt",
        "path": "$.vp_token[0]" }
    ]
  },

  "vp_token": [
    "eyJ...L1_SDJWT...~disc1~disc2~eyJ...L2_SDJWT...~disc3~eyJ...L3_SDJWT...~disc4~eyJ...KB-JWT..."
  ],

  "transaction_data_results": [
    {
      "type": "x402_payment_v2",
      "credential_id": "verifiable_intent_l3",

      "filled_authorization": {
        "from":        "0xWalletPasskeyAddress…",
        "to":          "0x209693Bc6afc0C5328bA36FaF03C514EF312287C",
        "value":       "10000",
        "validAfter":  "1714750800",
        "validBefore": "1714751100",
        "nonce":       "0xa1b2c3d4e5f6...32_bytes"
      },

      "eip712_signature": "0x<r:32 ‖ s:32 ‖ v:1>",

      "x402_envelope": {
        "x402Version": 2,
        "payload": {
          "authorization": "<same as filled_authorization above>",
          "signature": "0x<same as eip712_signature above>"
        },
        "resource": "https://x402.org/protected",
        "extensions": {},
        "accepted": "<the merchant's chosen accepts[] entry, echoed verbatim>"
      }
    }
  ]
}
```

### KB-JWT payload (last segment of `vp_token`)

The KB-JWT is what binds the credential presentation to the bundled
payment. Signed by the holder's passkey at presentation time. Shape:

```json
{
  "nonce":  "random-server-challenge-7f3a2b1c",
  "aud":    "https://x402.org",
  "iat":    1714750801,
  "sd_hash": "base64url(sha256(L3_SDJWT_with_disclosures))",

  "transaction_data_hashes":     ["base64url(sha256(transaction_data[0]))"],
  "transaction_data_hashes_alg": "sha-256"
}
```

By including `transaction_data_hashes` the holder cryptographically
commits to *exactly* the `transaction_data` blob shown in the consent
screen. Tamper with anything in `transaction_data` between request and
verification → hash mismatch → verification fails.

### The two signatures, one biometric

The wallet performs two distinct cryptographic operations within a
single user-verification event:

| Operation | Signs over | Result lives in |
|---|---|---|
| 1. Sign KB-JWT | JWT signing input (commits to `sd_hash` + `transaction_data_hashes`) | Last segment of `vp_token` |
| 2. Sign EIP-712 hash | `keccak256("\x19\x01" ‖ domainSeparator ‖ structHash)` for `TransferWithAuthorization` | `eip712_signature` field |

Same passkey, same biometric, two ECDSA-P256 outputs. The DC API surface
on Android (`androidx.credentials.DigitalCredential`) batches both
signings into one `getCredential()` call so the OS only fires user
verification once.

### What the merchant verifies, in order

```
1. Verify L1, L2, L3 SD-JWT signatures (issuer chain)
2. Verify KB-JWT signature against L3.cnf.jwk pubkey
3. Recompute hash(transaction_data[0]); compare to KB-JWT.transaction_data_hashes[0]
4. Recompute keccak256(EIP-712(filled_authorization))  →  eip712_hash
5. Verify eip712_signature against eip712_hash with L3.cnf.jwk pubkey
   (off-chain ECDSA-P256 verify; same key as KB-JWT)
6. If 1-5 pass: forward x402_envelope to facilitator for chain settlement
   On-chain: Coinbase Smart Wallet's isValidSignature uses RIP-7212 to
             re-verify eip712_signature before USDC.transferWithAuthorization
```

Step 5 is the architectural bridge: **the same passkey** that proves
the credential presentation also signs the x402 EIP-712 hash. The
chain side (RIP-7212 P-256 precompile) verifies that signature
unchanged from Path A — Step 5.4 adds the credential layer on top
without altering the on-chain settlement primitive.

## What to build

Four concrete pieces. Each is small.

### 1. SD-JWT issuance — `step5.4-vc/issuer.kt`
- Issuer key generated in software (or in another StrongBox alias)
- Issues an SD-JWT to the user's wallet at first launch
- Claims include: subject (user's passkey thumbprint via `cnf`), issuance
  timestamp, expiry, optional disclosures
- Spec reference: RFC 9901 SD-JWT, Verifiable Intent v0.1-draft for shape

### 2. SD-JWT presentation via DC API — extend `PasskeyWallet.kt` (Path A)
- Wallet stores the issued SD-JWT alongside the passkey credentialId
- Implements `androidx.credentials.DigitalCredential` to receive DC API
  requests from the merchant
- On request: parses `presentation_definition` + `transaction_data`,
  selects the matching SD-JWT, builds a presentation with selective
  disclosure
- Renders one consent screen showing both credential claims being
  shared and the bundled x402 payment
- On user verification: produces both the KB-JWT (with
  `transaction_data_hashes` committing to the payment) and the EIP-712
  signature for x402, returned together
- Spec reference: OID4VP `transaction_data` extension; W3C Digital
  Credentials API (working draft); Android Credential Manager DC API

### 3. SD-JWT + transaction_data verification — extend the x402 server
- Demo server: a tiny Kotlin/Python service that:
  - Returns 402 with a DC API request bundling the
    `presentation_definition` and the `transaction_data` (the x402
    payment template)
  - Receives back: `vp_token` (containing the SD-JWT chain + KB-JWT)
    plus `transaction_data_results` (containing the filled x402
    authorization + EIP-712 signature)
  - Verifies in order: SD-JWT issuer chain → KB-JWT against L3 cnf
    pubkey → `transaction_data_hashes` match → EIP-712 signature
    against same pubkey
  - On success: forwards the x402 envelope to the facilitator for
    chain settlement
  - On failure: returns 401 with a JSON error pointing at the failed
    check
- Spec reference: any standard SD-JWT library (Python: `sd-jwt`,
  Kotlin: `nimbus-jose-jwt` + manual disclosure handling); web3j or
  equivalent for off-chain ECDSA-P256 verify

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
2. **How does the issuer key bootstrap?** For the spike, a static
   `issuer.json` JWKS file served via GitHub Pages or a Cloudflare
   Worker. For production, this is a real CA-shaped problem.
3. **Selective disclosure granularity?** SD-JWT lets the holder reveal
   only some claims to each verifier. For a payment, the merchant
   probably needs: subject pubkey, expiry, max amount, allowed merchant.
   Other claims (e.g., user identity) stay disclosed only to higher-
   trust parties.
4. **DC API support on the smart wallet side?** Coinbase Smart Wallet's
   on-chain `isValidSignature` verifies the EIP-712 signature directly
   via RIP-7212 — no DC API awareness needed. The DC API integration
   lives entirely between merchant and wallet (off-chain). Confirm
   this stays true for whatever smart wallet variant the demo lands on.

## Pointers

- `PATH_A_NEXT.md` — required prerequisite (Path A milestones)
- `PATH_C_MPC.md` — alternative architecture, deferred
- `PATH_C_WIREFRAMES.md` — Path C UX sketches (some agent-management
  patterns may carry over to the VC presentation UX)
- Verifiable Intent spec: `verifiableintent.dev/spec/` (current 0.1-draft)
- AP2 repo: `github.com/google-agentic-commerce/AP2` (current v0.2,
  Python/Go/Android samples in `code/samples/`)
- SD-JWT spec: RFC 9901 (Selective Disclosure for JWTs), including the
  KB-JWT `transaction_data_hashes` claim that commits the holder's
  signature to bundled transactional data
- OID4VP: OpenID for Verifiable Presentations, including the
  `transaction_data` extension that carries the bundled payment
  authorization request alongside the credential ask
- W3C Digital Credentials API (working draft): the OS-level transport
  surface that lets a merchant issue one bundled request and receive
  one bundled response, with the wallet driving a single consent screen
- Android: `androidx.credentials.DigitalCredential` — the platform
  binding that hooks Credential Manager into wallet apps speaking DC API
- This is downstream of Step 5 (Path A); not a parallel track.
