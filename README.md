# strongbox-backed-x402-wallet

A personal experiment: hardware-backed x402 micropayments from an Android phone,
with the signing key wrapped by StrongBox and gated by biometrics.

End-to-end goal: tap fingerprint → sign EIP-712 TransferWithAuthorization →
USDC moves on Base Sepolia → response from the paid endpoint comes back.

## Status

Just initialized. See `CLAUDE.md` for the build plan and constraints.
See `research.docx` for the full background and rationale.

## Quick links

- Research doc with full background: `./research.docx`
- Build plan and agent context: `./CLAUDE.md`
- Step 1 (Node, no Android): `./step1-node/` (TODO)
- Step 2 (Kotlin CLI, no Android): `./step2-kotlin-cli/` (TODO)
- Step 3 (Android, hardcoded key): `./step3-android-hardcoded/` (TODO)
- Step 4 (Android, StrongBox-wrapped): `./step4-android-strongbox/` (TODO)

## How to work on this

1. Open this directory in your terminal
2. Run `claude` to start Claude Code
3. First message: "Read CLAUDE.md and research.docx, then let's do Step 1."

Each step lives in its own subdirectory and its own commit. Don't skip ahead.
