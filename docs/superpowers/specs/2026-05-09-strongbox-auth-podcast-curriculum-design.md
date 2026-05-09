# StrongBox + Mobile Auth + Agentic Commerce — NotebookLM Curriculum

Captured 2026-05-09. A self-paced study curriculum that uses NotebookLM
Audio Overviews as the primary learning vehicle. The artifact is a
sequenced set of *source bundles* — each generates one ~15-25 min
audio episode plus a chat interface against the same sources.

## Goal

Become fluent enough in (1) hardware-backed key storage on mobile, (2)
mobile authentication standards, and (3) agentic commerce primitives
to speak with confidence to developer, enterprise, and policy audiences.

The artifact is *spoken fluency*, not blog posts or talks. Those follow
naturally from fluency; they are not the goal of this curriculum.

## Why NotebookLM as the medium

- **Production-free.** No recording, editing, or scheduling. The user
  curates sources; NotebookLM generates the audio.
- **Source-grounded.** The audio is constrained to the sources fed in,
  so quality scales with curation discipline rather than speaking skill.
- **Listening-friendly.** Audio Overviews are the right length (~15-25
  min) for a commute or walk.
- **Interactive follow-through.** NotebookLM's chat interface lets the
  user interrogate any spot the audio glossed over — against the same
  sources, no hallucinated drift.

The tradeoff: NotebookLM is a passive listening tool by default. To
convert listening into speaking confidence, the curriculum mandates an
active follow-through loop after each episode (see *Listening Loop*
below). Without it, episodes feel productive but produce no fluency.

## Curriculum structure

14 episodes total: a 4-episode **Foundations** prequel, then a 10-episode
**Spine** that walks outward from "where the key lives" to "how an agent
uses it autonomously."

### Foundations (4 episodes)

Accessible, analogy-driven. Audience: someone with general software
engineering background but limited cryptography or blockchain exposure.
**No repo docs in source bundles** — Foundations exist to make the repo
docs readable later.

| # | Episode | Question it answers |
|---|---|---|
| F0 | How money moves on the internet | What stablecoins, gas, USDC, ERC-20s, and wallets actually are. What is in a transaction. Why signatures are at the center of all of it. |
| F1 | Crypto in 30 minutes | Hashes, keys, signatures. What a private key really is (a number). What signing actually does. Symmetric vs asymmetric in plain terms. |
| F2 | Authentication, authorization, and the password problem | Why passwords are bad. What "something you have + something you are" means. Challenge-response. Phishing. Why this conversation eventually requires hardware. |
| F3 | Where secrets live: the trust ladder | A tour from `/etc/shadow` → password manager → keychain → TEE → Secure Element → StrongBox. What "trust boundary" means concretely. *This episode makes Spine 1 land.* |

### Spine (10 episodes)

Technical, spec-anchored. Each episode pairs **1-2 of the user's repo
docs** (the synthesis lens) with **2-4 external specs** and **0-2
explainers** for accessibility. Guidance prompt for every Spine episode
begins:

> "Assume the listener has finished the Foundations track. Don't
> re-explain hashes, signatures, or the trust ladder. Do explain
> Android-specific or protocol-specific terms the first time they
> appear."

| # | Episode | Question it answers |
|---|---|---|
| 1 | Where private keys live on Android | TEE vs Secure Element vs StrongBox vs software keystore. The taxonomy nobody explains right. |
| 2 | What the biometric prompt actually authenticates | BiometricPrompt + CryptoObject, auth-per-use vs validity-duration, Class 3 biometrics. |
| 3 | The curve mismatch | Why secp256k1 doesn't live in StrongBox. Wrap-the-seed workaround. The ~1ms RAM window cost. |
| 4 | Passkeys, WebAuthn, FIDO2 | Authenticator/RP/ceremony, why passkeys use P-256, platform vs roaming, attestation. |
| 5 | Smart wallets and the EOA escape hatch | ERC-4337, ERC-6492 (counterfactual), ERC-1271 (`isValidSignature`), how a smart wallet escapes secp256k1. |
| 6 | EIP-3009 and x402 | TransferWithAuthorization, EIP-712 typed data, the 402 → payment → receipt flow, facilitators. |
| 7 | Verifiable Credentials for payments | SD-JWT + KB-JWT, OID4VP `transaction_data`, the W3C Digital Credentials API, single-biometric flow. |
| 8 | AP2: the agent payments protocol | How agents authorize, how merchants verify, where AP2 fits with x402, VCs, and smart wallets. |
| 9 | Delegation and autonomous mode | One biometric → scoped agent key, session keys, ERC-7710/7715, revocation, audit. |
| 10 | Threat models and honest tradeoffs | What each layer protects against and what it doesn't. The RAM window. Phishable recovery. Centralized facilitators. What's still missing in 2027. |

## Source bundle recipe

- **Sweet spot: 5-8 sources per notebook.** Fewer feels thin, more
  dilutes focus. NotebookLM accepts up to 50 but quality degrades past
  ~10 for a focused episode.
- **Foundations sources:** Computerphile / 3Blue1Brown YouTube,
  Cloudflare Learning Center, Dan Boneh's Coursera, Wikipedia, NIST
  publications (when accessible). Zero repo docs.
- **Spine sources:** 1-2 of the user's repo docs + 2-4 specs + 0-2
  explainer blog posts. The repo docs carry the synthesis voice; the
  specs carry the authority; the explainers exist only when a primary
  spec is unreadable without context.
- **Guidance prompt:** every notebook gets one. It names the listener's
  level, lists 3-5 questions the audio must answer crisply, and sets the
  tone (Foundations: patient + analogies; Spine: technical + assume
  Foundations completed).

## Listening loop

This is what converts NotebookLM from passive listening into speaking
fluency. **Do not skip.**

For each episode:

1. **Generate + listen once** — full attention, ~15-25 min, no
   multitasking on the first listen.
2. **Write a one-pager in your own words** — answer the episode's
   title-question in <300 words, no looking back at sources. Save to
   `learning/<NN>-<slug>.md` in this repo. *This is the step that builds
   fluency.* If you cannot write 300 words from memory, you did not
   absorb the episode — listen again.
3. **Use NotebookLM's chat** to interrogate any spot where your
   one-pager went vague. The chat answers from the same sources, no
   drift.
4. **Every 3 episodes, do a Feynman test** — record yourself (voice
   memo, 2-3 min) explaining the topic to an imaginary smart friend.
   Listen back. Where you hesitated = your next deep-dive bundle.
5. **After Foundations + Spine 1-3**, have one real conversation with
   someone (a security colleague, a dev friend) on the topic. Note
   where you stumble. Those gaps become deep-dive notebooks.

## Cadence

One notebook per week. ~25 min listening + 30-45 min for the one-pager
≈ ~75 min per episode. **~14 weeks total** to finish Foundations +
Spine. Roughly aligns with the existing blog post series in
`BLOGPOST_SERIES.md`: each Spine episode that maps to a planned post
serves as the research dump for that post.

## Detailed source bundles for the first 7 notebooks

The remaining 7 stay as topic stubs and get fleshed out as the user
progresses through the syllabus, using gaps surfaced by the Feynman
tests. Drafting all 14 upfront over-commits to a curriculum the user
hasn't started yet.

URLs below are pointers; the user verifies and substitutes equivalents
if anything has moved.

---

### F0 — How money moves on the internet

**Sources:**
1. Wikipedia: *Stablecoin*, *ERC-20*
2. ethereum.org: "What is gas?" and "Accounts" intro pages
3. Circle's USDC FAQ / developer docs (focus on what USDC actually is on
   chain — an ERC-20 with mint/burn permissions)
4. 3Blue1Brown YouTube: "But how does bitcoin actually work?" (the
   transaction-and-signature parts)
5. *Mastering Ethereum* by Antonopoulos & Wood — chapter 4 (Cryptography
   and chapter on accounts) — published online for free at the book's
   GitHub repo
6. Vitalik Buterin: "Visions, Part 1" or any of his early intro posts on
   what Ethereum accounts are

**Guidance prompt:**
> "Listener is a software engineer who has heard of Bitcoin and Ethereum
> but has never actually held crypto or done an on-chain transaction. By
> the end of this audio they should be able to explain (1) what a
> stablecoin like USDC actually is at the contract level, (2) what 'gas'
> is and why it exists, (3) what's inside a transaction and what
> 'signing' it means, (4) the difference between an externally owned
> account and a smart contract account, (5) why ERC-20 transfers and ETH
> transfers look different on chain. Be patient. Use analogies (the
> ledger metaphor is fine; the 'gas tank' metaphor for gas is fine).
> Don't get into rollups, MEV, or staking — those are out of scope."

---

### F1 — Crypto in 30 minutes: hashes, keys, signatures

**Sources:**
1. Computerphile YouTube: "Hashing Algorithms and Security" (Mike Pound)
2. Computerphile YouTube: "Public Key Cryptography" (Robert Miles)
3. Cloudflare Learning Center: "What is encryption?", "What is a digital
   signature?", "What is public key cryptography?"
4. Dan Boneh's *Cryptography I* (Coursera) — week 1 lecture notes /
   transcripts (publicly available in many mirrors)
5. Wikipedia: *Cryptographic hash function*, *Digital signature*,
   *Public-key cryptography* (the introductions only — don't feed in
   the full articles)
6. 3Blue1Brown YouTube: the signature-explanation portion of "But how
   does bitcoin actually work?" (yes, used twice — different framing)

**Guidance prompt:**
> "Listener finished F0 — they know what an on-chain transaction is.
> They have NOT seen cryptography explained well before. By the end they
> should be able to explain in plain language (1) what a hash function
> is and what 'collision-resistant' means, (2) the difference between
> symmetric and asymmetric encryption with one good example of each, (3)
> what a digital signature actually proves and what it does NOT prove,
> (4) why 'a private key is a really big number' is mathematically
> accurate, not a metaphor. Use analogies. Be patient. Avoid the math
> notation entirely — say 'a function that takes input X and produces
> output Y' rather than 'f(x) = y'."

---

### F2 — Authentication, authorization, and the password problem

**Sources:**
1. Cloudflare Learning Center: "What is authentication?", "What is
   authorization?", "What is a phishing attack?"
2. NIST SP 800-63B *Digital Identity Guidelines* — Authentication and
   Lifecycle Management, executive summary + section 4 (AAL levels)
3. Troy Hunt blog: "The only secure password is the one you can't
   remember" (or any of his canonical posts on password failure modes)
4. Auth0 blog: "Introduction to Passwordless Authentication" (or
   equivalent recent post)
5. Wikipedia: *Challenge-response authentication*,
   *Multi-factor authentication* (introductions only)
6. FIDO Alliance: "What is FIDO?" intro page

**Guidance prompt:**
> "Listener finished F0 and F1 — they understand signatures and basic
> crypto. They've used passwords their whole life and have probably used
> 2FA. They have NOT thought carefully about why passwords fail. By the
> end they should be able to explain (1) the difference between
> authentication and authorization in one sentence each, (2) the three
> factor categories (knowledge, possession, inherence) with one real
> example of each, (3) why passwords are phishable and what
> challenge-response authentication does that fixes this, (4) why FIDO
> exists as a standard and what problem it claims to solve. Be patient.
> Build to the question 'what kind of authenticator can we trust to do
> challenge-response on our behalf?' — that question sets up F3."

---

### F3 — Where secrets live: the trust ladder

**Sources:**
1. Cloudflare Learning Center: "What is a Trusted Execution Environment
   (TEE)?"
2. Apple *Platform Security* guide — the Secure Enclave section
   (it's the best-written SE explainer in the industry, and even though
   the user is on Android, the concepts transfer)
3. Android developer docs: "Android Keystore system" overview
4. AOSP source.android.com: "StrongBox" feature page
5. Wikipedia: *Trusted Execution Environment*, *Secure cryptoprocessor*,
   *Hardware security module* (introductions only)
6. A Trail of Bits or Project Zero blog post on TEE / SE attacks
   (honesty about limits — pick the most recent reputable analysis)

**Guidance prompt:**
> "Listener finished F0-F2 — they understand authentication and why we
> need somewhere safe to do challenge-response. By the end of this audio
> they should be able to explain (1) what a 'trust boundary' is in
> concrete terms (e.g., 'this code can read those bytes; that code
> cannot'), (2) the ladder from plaintext file → encrypted file →
> OS-protected keystore → TEE → discrete Secure Element, with what each
> level protects against, (3) the difference between a TEE (TrustZone)
> and a discrete Secure Element (Titan M2 on Pixel), (4) what 'StrongBox'
> means in Android terminology and why it's named that way, (5) what
> 'hardware-backed' is honestly worth — it's not magic, and SE attacks
> exist. Be patient with the OS-level concepts. This is the episode that
> sets up the entire Spine."

---

### Spine 1 — Where private keys actually live on Android

**Sources:**
1. `step4-android-strongbox/app/src/main/java/app/x402spike/SecureWallet.kt`
   (the user's own code — the synthesis voice)
2. `PATH_A_NEXT.md` (the user's own analysis of the Path B vs Path A
   tradeoffs — relevant context for "where keys live")
3. Android developer docs: "Android Keystore system" (full page, not
   just the overview used in F3)
4. AOSP source.android.com: "StrongBox" feature page (full, including
   `KeyInfo.getSecurityLevel()` semantics)
5. Google Security Blog: the Pixel Titan M / Titan M2 announcement posts
6. A reputable analysis of TEE vs SE differences on modern Android (e.g.,
   from Trail of Bits, NCC Group, or Quarkslab)

**Guidance prompt:**
> "Listener has finished Foundations. They know what a TEE is, what a
> Secure Element is, and what a trust boundary means. They are an
> Android developer who has used `KeyStore` casually but never looked at
> the security level of the keys they create. By the end they should be
> able to explain (1) the four security levels Android exposes
> (`SOFTWARE`, `TRUSTED_ENVIRONMENT`, `STRONGBOX`, and the rare
> `KEYSTORE`) and how to query them, (2) what hardware backs each level
> on Pixel and Samsung devices, (3) the silent-fallback problem and why
> code must log the actual security level it got, (4) what
> `setUserAuthenticationRequired` and `setIsStrongBoxBacked` do at the
> Keymaster level, (5) the practical difference between TrustZone and
> Titan M2 for an attacker. Be technical. Don't re-explain what a TEE
> is. Reference the user's `SecureWallet.kt` code as a worked example."

---

### Spine 2 — What the biometric prompt actually authenticates

**Sources:**
1. `step4-android-strongbox/app/src/main/java/app/x402spike/SecureWallet.kt`
   (the BiometricPrompt + CryptoObject section)
2. Android developer docs: "BiometricPrompt" API reference, including
   `CryptoObject`
3. Android Compatibility Definition Document (CDD) — section on
   biometric classes (Class 1 / Class 2 / Class 3 strength)
4. Android developer docs: "Use a key" — the
   `setUserAuthenticationRequired` /
   `setUserAuthenticationValidityDurationSeconds` /
   `setUserAuthenticationParameters` documentation
5. Google Security Blog or AOSP docs on biometric strength evaluation
   (SAR/IAR/SPI metrics)
6. A blog post on the BiometricPrompt API gotchas (e.g., the
   `KeyPermanentlyInvalidatedException` flow)

**Guidance prompt:**
> "Listener finished Foundations and Spine 1. They know where keys live
> on Android. By the end they should be able to explain (1) what
> happens when `BiometricPrompt.authenticate(CryptoObject)` succeeds —
> specifically, what the kernel just unlocked and for how long, (2) the
> difference between `setUserAuthenticationRequired(true)` with
> `validityDuration = -1` (auth-per-use) versus `validityDuration > 0`
> (time-bound), and why this repo's CLAUDE.md flags the latter as an
> anti-pattern, (3) Class 1 / 2 / 3 biometrics and why only Class 3 is
> allowed to gate cryptographic operations, (4) what
> `KeyPermanentlyInvalidatedException` means and when it fires, (5) the
> exact threat the biometric prompt does and does not protect against
> (it does not protect against a compromised app on the same device
> with the user's permission). Be technical. Reference the
> `SecureWallet.kt` biometric flow."

---

### Spine 3 — The curve mismatch

**Sources:**
1. `PATH_A_NEXT.md` (the user's analysis of why Path B exists and what
   Path A solves)
2. `step4-android-strongbox/app/src/main/java/app/x402spike/SecureWallet.kt`
   (the wrap-the-seed implementation)
3. Android Keymaster HAL definition — supported algorithms section
   (`KeyProperties` source on AOSP)
4. EIP-3009 spec
5. Cloudflare Learning Center: "What is elliptic curve cryptography?"
   (for the listener who finished F1 but didn't get curves specifically)
6. A reputable explainer on why Bitcoin/Ethereum chose secp256k1 and why
   most other systems chose P-256 / secp256r1 (e.g., Cloudflare blog or
   IETF historical context)

**Guidance prompt:**
> "Listener finished Foundations and Spine 1-2. They know what a digital
> signature is and where Android puts keys. They do NOT yet know what an
> 'elliptic curve' is or why secp256k1 vs P-256 matters. Walk from 'a
> private key is a number' to 'why Ethereum's number lives in a
> different mathematical world than what StrongBox guards natively.' By
> the end they should be able to explain (1) what an elliptic curve is
> at a coffee-shop level (a set of points on a specific kind of curve —
> no need for the math), (2) which curves StrongBox supports (P-256, in
> the ECDSA sense) and which it does not (secp256k1, Ed25519), (3) why
> every Android crypto wallet ends up wrapping a software-generated
> secp256k1 seed under a StrongBox AES key, (4) what the ~1ms RAM window
> means in concrete attacker terms, (5) what Path A (passkey + smart
> wallet) does differently to eliminate the window. Use analogies for
> curves themselves; be technical for everything else. Reference the
> repo's PATH_A_NEXT.md as the synthesis voice."

---

## Repo layout

```
docs/superpowers/specs/
  2026-05-09-strongbox-auth-podcast-curriculum-design.md   ← this file

learning/                                                  ← user creates as they go
  F0-money-on-the-internet.md
  F1-crypto-in-30-min.md
  F2-auth-and-passwords.md
  F3-trust-ladder.md
  S1-where-keys-live-android.md
  ...
```

Each `learning/*.md` is the user's own one-pager (step 2 of the
listening loop). These are the fluency artifacts; the audio is the
input.

## What this curriculum is *not*

- **Not a podcast for an audience.** The listener is the user. Google
  has been expanding NotebookLM sharing features; treat the licensing
  as personal-use unless explicitly verified otherwise before any
  public distribution.
- **Not a substitute for shipping code.** Foundations + Spine produce
  fluency to *speak about* the work; they do not advance Path A, Step
  5.4, or Step 8 implementation.
- **Not a replacement for the blog series.** The blog series in
  `BLOGPOST_SERIES.md` is the *output* artifact. This curriculum is the
  *input* that makes the blog series easier to write.
- **Not a fixed plan.** If a Feynman test surfaces a gap that needs a
  deep-dive bundle, that takes precedence over the next scheduled
  episode. The Spine is a default order, not a contract.

## Open questions

1. **Tooling for the one-pager step.** Should `learning/*.md` files use
   a template (question / 300-word answer / 5 follow-ups), or stay
   freeform? Defer until after F0-F1 to see what naturally emerges.
2. **Visual companion.** Some episodes (F3 trust ladder, Spine 1
   keystore taxonomy, Spine 4 WebAuthn ceremony) would benefit from
   diagrams. NotebookLM produces audio only. Consider whether to draft
   diagrams alongside one-pagers or skip.
3. **Blog series tie-in.** Each Spine episode that maps to a planned
   blog post (Spine 3 → Post 2; Spine 5 → Post 3; Spine 7-8 → Post 4;
   Spine 9 → Post 5) should produce a "research dump" section in the
   one-pager that the post can pull from. Worth formalizing once the
   first such mapping happens.

## Next step

Implementation plan via the `superpowers:writing-plans` skill. The plan
breaks this design into concrete, verifiable steps: directory setup,
F0 source-bundle generation, F0 listen + one-pager, repeat.
