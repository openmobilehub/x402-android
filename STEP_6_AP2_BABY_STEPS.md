# Step 6 — AP2 + VI + x402 baby steps

Captured 2026-05-04 after Step 4.2 (payment history) shipped and Path A
+ Step 5.4 + Step 5 (Hedera) plans were committed. This doc is the
incremental build plan for the agent-flow side of the Step 5.4 vision.
Lives in this repo for the early steps; will likely fork out to its
own repo when scope grows (see "When to split").

## Where we are

- ✅ Steps 1–4 + 4.2 shipped to https://github.com/openmobilehub/x402-android
- ✅ Path A (passkey + smart wallet) plan documented in `PATH_A_NEXT.md`
- ✅ Step 5.4 (VC-bound x402, end-to-end) plan documented in
     `STEP_5_4_VC_PAYMENTS.md`
- ✅ Step 5 cross-chain Hedera plan in `STEP5_HEDERA.md`
- ⬜ Nothing implemented yet on the agent side — AP2 is just a clone
     away but nothing has been built in this repo

## The goal

End-to-end demo where:

1. An AI agent makes a purchase decision on the user's behalf
2. The agent presents a **Verifiable Intent SD-JWT** to a merchant
   proving it was authorized (scoped: amount, merchant, window)
3. The merchant verifies the credential **off-chain**
4. The merchant returns an **x402 payment-required** challenge
5. The user's **StrongBox-backed wallet** signs the EIP-712 hash
6. Settlement on Base Sepolia, **visible on BaseScan**

End state: an artifact nobody else has demonstrated publicly — agent +
VI + x402 + visible on-chain settlement, all using P-256 throughout.

## Nine baby steps

Each step is small, testable, and adds one concept. Don't skip ahead.

### Step 0 — Run AP2 as-is (½ day)

```sh
git clone https://github.com/google-agentic-commerce/AP2 step6-ap2-spike/upstream
cd step6-ap2-spike/upstream
git rev-parse HEAD > ../UPSTREAM_PIN.txt   # pin the commit hash
bash code/samples/python/scenarios/a2a/human-present/cards/run.sh
```

Watch one shopping flow play out: agent ↔ merchant ↔ (mocked) card
payment. Don't change anything.

**Done criterion:** can describe in two sentences what the agent does,
what the merchant does, and where money would actually move.

### Step 1 — Replace just the agent with our own (½ day)

Fork the sample into `step6-ap2-spike/agent/`. Rewrite the agent role
in your own Python file. Same behavior, same output, but now you own
it. Keep the merchant and the mocked card rail untouched.

**Done criterion:** `agent/main.py` runs against the upstream merchant
sample and completes a flow.

### Step 2 — Inject a (mock) Verifiable Intent into the agent's request (½ day)

Generate a hardcoded SD-JWT in Python. Suggested libs: `sd-jwt-python`,
`pyjwt`. Have the agent attach it to the AP2 payment request as a
structured field. Don't verify it on the merchant side yet; just print
"received credential" to confirm it travels.

**Done criterion:** agent's request contains a real SD-JWT; merchant
logs it on receipt.

### Step 3 — Add merchant-side VI verification (1 day)

Stand up a tiny issuer service (one Python file, or a hardcoded JWKS)
in `step6-ap2-spike/issuer/`. Make the merchant verify the SD-JWT
signature chain, check claims (`exp`, `aud`, `cnf`, scope), accept or
reject. Reject path returns 401; accept path proceeds to mocked card
payment.

**Done criterion:** flipping a single byte in the SD-JWT causes the
merchant to reject the flow.

### Step 4 — Swap the card rail for x402 / Base Sepolia (1–2 days)

The biggest single jump. Replace AP2's mocked card settlement with a
real x402 challenge:

1. Merchant returns a 402 with the x402 `payment-required` header
   instead of "card auth required"
2. Agent uses a tiny **software** ECDSA key (no StrongBox yet — just
   `eth_keys` in Python or `web3.py`) to sign the EIP-712 hash
3. Submit the envelope to a facilitator (Coinbase's hosted one, free
   tier on Base Sepolia)
4. Settle on-chain
5. Agent prints the BaseScan URL

**Done criterion:** an AP2 flow on your laptop produces a real
on-chain transaction visible on BaseScan. No phone involved yet.

This is the "agentic commerce on testnet" hello world. Worth its own
commit and possibly a blog post.

### Step 5 — Pin the wallet to the existing Step 4 phone wallet (1 day)

Replace the agent's software ECDSA key with a call to the **Step 4
Android app**. The agent runs on your laptop; signing happens on the
Pixel:

- Agent sends the EIP-712 hash to the phone (deeplink, QR code, or LAN
  HTTP — whatever's simplest for the spike)
- Phone shows the bundled credential + payment in a consent screen
- Biometric → seed unwraps → signs → returns `(r, s, v)` to the agent
- Agent finalizes the x402 envelope, submits, settles

**Done criterion:** AP2 flow on laptop, biometric on phone, payment
lands on BaseScan with a StrongBox-signed signature. **The full Step
5.4 architecture is now working** — just with Path B (seed-in-RAM) on
the wallet side.

This is genuinely a demo-worthy milestone. Stop here if a weekend is
all the time you have.

### Step 6 — Migrate the wallet from Path B (Step 4) to Path A (passkey)

Per `PATH_A_NEXT.md` Milestones M1–M4. By the end:
- Phone uses a P-256 passkey in StrongBox (no seed-in-RAM window)
- Smart wallet on Base verifies P-256 via RIP-7212
- Same UX, structurally stronger security claim

**Done criterion:** the AP2 flow from Step 5 still works, but the
wallet is now Path A-shaped — no plaintext seed ever in app RAM.

### Step 7 — One biometric via DC API + OID4VP `transaction_data` (1–2 days)

Today (after Step 6) the user does **two** biometrics: one for the
credential KB-JWT, one for the x402 envelope. Bundle them via the W3C
Digital Credentials API per the wire format committed in
`STEP_5_4_VC_PAYMENTS.md`:

- Merchant returns a single DC API request with both
  `presentation_definition` and `transaction_data`
- Phone shows one consent screen, fires one biometric
- Returns `vp_token` + signed x402 envelope from one user-verification
  event

**Done criterion:** exact same end-to-end flow as Step 6, but with one
fingerprint touch instead of two.

### Step 8 — Human-not-present mode (the agentic finale)

AP2 v0.2 introduced "Human Not Present" payments — agent signs
autonomously with a delegated key, no biometric per payment. Add this
last:

- L2 SD-JWT delegates spending authority to the agent's own keypair
  (one biometric at delegation, never again)
- Per-payment: agent signs both KB-JWT (L3) and x402 envelope with its
  own key — zero user biometrics
- User sees per-payment notifications and can revoke at any time

**Done criterion:** a wallet you've delegated to spends 0.01 USDC
autonomously when the agent decides to, with no user interaction at
the moment of payment.

## Track parallelism

```
Steps 0-5     →  AP2 + VI + x402 + Step 4 wallet (works end-to-end)
                     │
Step 6        →  Path A migration (per PATH_A_NEXT.md, parallel-ish)
                     │
Steps 7-8     →  Single biometric, then agent-autonomous
                     │
Goal          →  Full Step 5.4 vision realized
```

Steps 0–5 don't strictly require Path A — start there. Path A is a
parallel track that buys a stronger security claim once it lands.

## Why this lives in this repo (for now)

- The four-step build + future-paths narrative is unified here
- Cross-references already exist (`STEP_5_4_VC_PAYMENTS.md` mentions AP2)
- Visitors land at one URL and see the full story
- Early steps (0–3) are mostly Python forks and small files — no
  conflict with the existing Kotlin/Node setup
- Sub-directory now, separate repo later is a one-`git filter-repo`
  away if scope justifies the split

## When to split into its own repo

Move `step6-ap2-spike/` to a new repo when **at least one** is true:

1. The directory grows past ~10 files of original code (excluding the
   pinned upstream fork)
2. Non-mobile contributors start engaging — the agent + merchant code
   should not require wading through Kotlin Android wallet code
3. The agent + merchant + facilitator needs its own CI / deployment
   pipeline (the merchant + facilitator deploys server-side; the
   Android repo doesn't need that)
4. Step 4 of the baby steps lands — at that point you have a
   substantive agentic-commerce project, not just a wallet experiment

At split time:
- Move `step6-ap2-spike/` to its own repo
- Leave a stub README in this repo pointing at the new location
- Cross-link: agent repo's README references the x402-android wallet

Naming options if/when split:
- Under `dzuluaga/`: cleanest. `x402-agent`, `agentic-commerce-spike`,
  or `ap2-x402-bridge`
- **Not OMH** — OMH is mobile-focused; agent/merchant/facilitator code
  is multi-language and runs server-side, doesn't fit OMH's slot
- **Possibly upstream** — submit a sample to the AP2 repo itself
  (`google-agentic-commerce/AP2`) demonstrating x402 settlement.
  Check their contribution policy at Step 4 time.

## Risks and dependencies

1. **AP2 is v0.2.** Message shapes may change. Pin the upstream commit
   hash in `UPSTREAM_PIN.txt` at Step 0; update deliberately, not
   continuously.
2. **VI is v0.1-draft.** Same caveat. Both are early specs — the
   architectural insight transfers, but the wire format may shift.
3. **Python dependency footprint.** AP2 samples use Gemini 3.1 +
   various Python tooling. The local env will be heavier than the
   existing Kotlin/Node setup. Use a venv per sub-directory; don't
   pollute the repo's top-level dep story.
4. **Self-hosted facilitator at Step 4+.** Coinbase's hosted facilitator
   works for Base Sepolia today. If the demo needs a different chain
   (Hedera per `STEP5_HEDERA.md`) or different settlement semantics,
   that's a Step 5-or-later concern.

## Open questions

1. **Which AP2 scenario template fits best for our flow?** Start with
   `human-present/cards/`; later move to `human-not-present/cards/`
   then add an x402 variant. Confirm at Step 0 by reading the upstream
   structure.
2. **How does the agent communicate with the phone wallet at Step 5?**
   Options: deeplink, QR code, LAN HTTP, BLE. Pick at Step 5; deeplink
   is probably the simplest for a single-user spike.
3. **Issuer key bootstrap?** Same answer as `STEP_5_4_VC_PAYMENTS.md`:
   for the spike, a static `issuer.json` JWKS served via GitHub Pages
   or a Cloudflare Worker. Production is a real CA-shaped problem.

## Pointers

- `PATH_A_NEXT.md` — passkey + smart wallet milestones (Step 6
  dependency, parallel track)
- `STEP_5_4_VC_PAYMENTS.md` — wire format and architecture this spike
  builds toward
- `STEP5_HEDERA.md` — cross-chain extension after Path A
- AP2: `github.com/google-agentic-commerce/AP2` (v0.2,
  Python/Go/Android samples in `code/samples/`)
- Verifiable Intent: `verifiableintent.dev/spec/` (v0.1-draft)
- x402 v2 spec: this repo's existing implementation in Steps 1–4
- SD-JWT: RFC 9901
- OID4VP `transaction_data` extension: see OpenID for VP draft
- W3C Digital Credentials API: working draft

## Reminder for future-me

- One step at a time. Don't collapse Steps 1–3 in one sitting.
- Pin the AP2 upstream commit hash. Re-pin only deliberately.
- Steps 0–5 are independent of Path A. Don't block on Path A.
- Step 5 alone is a demo-worthy artifact — stop there if scope demands.
