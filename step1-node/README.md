# Step 1 — Node.js x402 client

One file (`pay.js`) that pays a Base Sepolia x402 endpoint with USDC and prints
the resulting txHash. No Android, no StrongBox, no abstraction. The point is to
feel the protocol once before any of the platform code goes in.

## Install

```sh
cd step1-node
npm install
```

## Generate a test private key (safely)

We need a fresh secp256k1 key that has *only ever existed for this experiment*.
Two clean ways:

**Option A — viem one-liner** (we already depend on viem):

```sh
node -e "import('viem/accounts').then(m => { const pk = m.generatePrivateKey(); const acc = m.privateKeyToAccount(pk); console.log('address:', acc.address); console.log('private key:', pk); })"
```

**Option B — OpenSSL** (no JS deps, equivalent entropy):

```sh
openssl rand -hex 32
```

Then derive the address from it once with viem to know which address to fund.

Whichever you use:

1. Copy `../.env.example` to `step1-node/.env`
2. Paste the private key into `PRIVATE_KEY=` (with or without `0x` prefix)
3. **Never** put real funds at this address. Testnet only. Throw the key away
   when this experiment is done.

`.env` is already gitignored at the repo root — verify with `git status` that
it does not appear before you commit anything.

## Fund the test address

You need two things on Base Sepolia:

| What | Faucet |
| --- | --- |
| **Base Sepolia USDC** (this is what gets paid) | https://faucet.circle.com — pick "Base Sepolia", paste your address |
| **Base Sepolia ETH** (only needed if you ever broadcast a tx yourself; x402 normally does not require it because the facilitator pays the gas) | https://faucet.quicknode.com/base/sepolia or any Base Sepolia ETH faucet |

For x402's `exact` scheme on USDC v2, **you do not need ETH** — the Coinbase
facilitator submits `transferWithAuthorization` and pays the gas. USDC alone is
enough. Grab the ETH only if you want to do non-x402 sanity transactions.

Confirm the balance shows up on the address before running the script:
https://sepolia.basescan.org/address/&lt;your-address&gt;

## Pick a public x402 demo endpoint

The x402 ecosystem has a few free testnet endpoints you can hit. The site at
**https://x402.org** lists currently-live demo URLs (look for a "demo" or
"protected" link in the navigation, or the bazaar listing).

If the public demo is down or has moved, the 5-line fallback is to run our own
locally with `@x402/express` — but for Step 1 we just want to hit something
public so we know the whole network path works. Pick one demo URL from x402.org
and pass it to the script.

We'll do the actual run together, so just have a URL ready.

## Run

```sh
node pay.js <demo-url>
```

## What success looks like

```
payer:   0xYourTestAddress
network: eip155:84532 (Base Sepolia)
target:  https://...

status:  200
payment: {
  "success": true,
  "transaction": "0xabc123...",
  "network": "base-sepolia",
  "payer": "0xYourTestAddress"
}

basescan: https://sepolia.basescan.org/tx/0xabc123...

body:
{ ...whatever the demo endpoint returns... }
```

Click the basescan URL. You should see a `transferWithAuthorization` call from
the Coinbase facilitator's address moving USDC out of *your* address into the
demo's recipient. That confirms:

- Your key signed a valid EIP-3009 authorization
- The facilitator accepted it and broadcast on-chain
- USDC actually moved
- The protected endpoint then served the response

That's the whole loop. After this works and is committed, we move to Step 2
(Kotlin CLI doing the same thing).

## Failure modes worth knowing

- `http 402` with no retry — usually means the response did not advertise a
  scheme/network we registered. Re-read the server's `x402Version` and `accepts`
  array; we currently register only `eip155:84532`.
- `insufficient funds` from the facilitator — your test address has no Base
  Sepolia USDC. Re-check the faucet.
- `nonce already used` — rare; the script generates a fresh random nonce per
  request. If you see this, re-run.
- `PRIVATE_KEY missing` — `.env` is in the wrong directory or wrong format.
  It must be at `step1-node/.env` and contain `PRIVATE_KEY=0x...`.
