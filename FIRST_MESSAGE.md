# First message to Claude Code

Once you've `cd`'d into this directory and run `claude`, paste this as
your first message. It's deliberately scoped to one step.

---

Read `CLAUDE.md` and `research.docx`. Confirm you understand the build
plan and the four-step sequencing.

Then let's do Step 1 only — the Node.js script that makes one x402
payment on Base Sepolia and prints the txHash.

Set up a minimal `step1-node/` subdirectory:
- `package.json` with only the dependencies we actually need
- `pay.js` that reads PRIVATE_KEY from `.env`, takes a URL as argv[2],
  and runs the x402 flow against it
- A `README.md` in that subdirectory with run instructions

Use `@x402/axios` (or `x402-axios`, whichever is current) as the client
SDK so we don't reinvent the protocol envelope.

Don't touch Android yet. Don't write any Kotlin yet. Don't install any
StrongBox-related dependencies yet.

When the script is ready, walk me through:
1. How to generate a test private key safely
2. Which faucets to use for Base Sepolia USDC and ETH
3. Which public x402 demo endpoint we'll hit first
4. What I should expect to see when it works (response shape, txHash format)

Then we run it together. After it works and I see the txHash on BaseScan,
we commit and only then move to Step 2.

---

# Subsequent messages

After Step 1 lands and is committed:
> Great. Step 2 — Kotlin CLI version of the same thing.

After Step 2 lands and is committed:
> Step 3 — minimal Android app with hardcoded key in BuildConfig.

After Step 3 lands and is committed:
> Step 4 — replace the hardcoded key with a StrongBox-wrapped seed.
> This is the demo. Take it slow.

# When something breaks

> Show me the full stack trace and the last 20 lines of logcat.
> What's the security level of the key being used? Don't guess —
> log `KeyInfo.getSecurityLevel()` and show me the actual value.

# When you're stuck on StrongBox specifically

> Let's check if this is a silent fallback. Print the security level,
> the key spec, and the device's `PackageManager.FEATURE_STRONGBOX_KEYSTORE`
> result. We'll figure out from there whether the key is actually in
> StrongBox, fell back to TEE, or is misconfigured.
