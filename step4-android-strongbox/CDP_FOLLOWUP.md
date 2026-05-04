# Step 4.1 (deferred) — Hybrid Recharge via CDP API

Captured 2026-05-03 so we don't lose context.

## What's already in Step 4

Each wallet card has a **Recharge** button that:

1. Copies the wallet address to clipboard
2. Opens `https://faucet.circle.com` in the system browser
3. User long-presses the input field, taps Paste, picks Base Sepolia, submits

This works zero-config and always will. Circle's faucet does not accept a
URL query param to pre-fill the address (verified by inspecting the page).

## What we want to add

Same Recharge button, but if the user has CDP API credentials configured,
skip the browser entirely and request the faucet drip via Coinbase
Developer Platform's REST API. One tap, no paste.

This is **progressive enhancement**, not a replacement:

- No `CDP_API_KEY_NAME` set → existing manual flow.
- Both CDP fields set → API path with manual flow as fallback on error.

## Detection

`local.properties` already has slots for `CDP_API_KEY_NAME` and
`CDP_API_KEY_PRIVATE_KEY` (commented out). `app/build.gradle.kts` reads
those into `BuildConfig`. The button checks
`BuildConfig.CDP_API_KEY_NAME.isNotEmpty()` at click time.

## CDP API surface

Endpoint (subject to docs check before implementing — see
https://docs.cdp.coinbase.com/api/v2/faucet):

```
POST https://api.cdp.coinbase.com/platform/v2/faucet
Authorization: Bearer <ES256 JWT>
Content-Type: application/json

{
  "network": "base-sepolia",
  "token": "usdc",
  "address": "0x..."
}
```

Free tier per CLAUDE.md: ~1000 drips/month per API key.

## Auth — the non-trivial part

CDP uses **ES256 JWT** (P-256 / secp256r1, not Ethereum's secp256k1).
Each request needs a fresh JWT, signed at request time:

- Header: `{ "alg": "ES256", "typ": "JWT", "kid": <key_name>, "nonce": <random> }`
- Payload: `{ "iss": "cdp", "sub": <key_name>, "aud": ["cdp_service"],
   "nbf": <now>, "exp": <now+120s>, "uris": ["POST api.cdp.coinbase.com/platform/v2/faucet"] }`
- Signed with the private key from `CDP_API_KEY_PRIVATE_KEY` (PKCS#8 PEM)

BouncyCastle is already on the classpath transitively via web3j — we use
it for P-256 keys + ES256 signing without adding a dependency. Plain
`java.security.Signature` with `SHA256withECDSA` provider, then convert
the DER signature to JWS R||S form.

## File plan

| File | Lines | Purpose |
| --- | --- | --- |
| `app/build.gradle.kts` | +5 | Read `CDP_API_KEY_NAME` + `CDP_API_KEY_PRIVATE_KEY` into BuildConfig |
| `app/src/main/java/app/x402spike/CdpJwt.kt` | ~80 | ES256 JWT minter using BouncyCastle |
| `app/src/main/java/app/x402spike/CdpFaucetClient.kt` | ~70 | OkHttp POST, JSON in/out |
| `MainActivity.kt` | +30 | Hybrid Recharge handler |
| `local.properties.example` | +4 | Document the two CDP fields |

Total: ~190 lines.

## UX details

- API path: show a Snackbar "Requesting CDP faucet drip…", await response,
  then show "Sent — txHash 0x… (refresh in ~30s)". Auto-trigger one
  Blockscout balance refresh after a 45s delay.
- API failure: fall back to the manual flow with a Snackbar explaining
  why ("CDP rate limit hit — opening web faucet").
- Don't auto-retry. CDP's free tier rate limit is per-key per-day; a
  silent retry loop will burn through it.

## Why this is the right next step *after* Step 4 commits, not before

CLAUDE.md's Step 4 deliverable is "StrongBox-wrapped seed + biometric +
end-to-end on-chain payment." That is done. CDP integration is polish —
real polish, not vanity polish — but it's a separate commit so the diff
stays scoped.

It also sets up the CDP facilitator path for later: the same JWT signer
gets reused when we eventually move off the public Coinbase facilitator
to a self-hosted (or rate-limit-free) one for the agentic-commerce
gateway.
