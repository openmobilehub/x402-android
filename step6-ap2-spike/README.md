# Step 6 spike — AP2 + Verifiable Intent + x402

This directory captures the agent-flow side of the Step 5.4 vision: an
AI agent presents a Verifiable Intent credential authorizing a payment,
the merchant verifies it, and settlement happens via x402 on Base
Sepolia. The Android wallet (Step 4 today, Path A eventually) signs the
EIP-712 hash.

## Status

Scaffold only. No implementation yet. See
[`../STEP_6_AP2_BABY_STEPS.md`](../STEP_6_AP2_BABY_STEPS.md) for the
nine-step plan to build this up incrementally.

## Why this lives here (for now)

The work fits the existing sequenced narrative — Steps 1–4 built the
x402 wallet, Path A makes it stronger, Step 5.4 layers credentials on
top. This spike is the "credential layer's reference implementation"
piece. Keeping it here for early baby steps preserves the unified
showcase. When scope grows (likely around baby step 4: real x402
settlement from the agent flow), this will probably move to its own
repo. See `STEP_6_AP2_BABY_STEPS.md` for the split criteria.

## Dependencies

Forks from upstream **AP2 v0.2** (https://github.com/google-agentic-commerce/AP2),
specifically the Python samples under `code/samples/python/scenarios/`.
Pin to a specific commit hash when you start; don't track HEAD.

Also touches:
- **Verifiable Intent v0.1-draft** (https://verifiableintent.dev/spec/) —
  SD-JWT credential format
- **x402 v2** (this repo's Steps 1–4) — settlement protocol
- **Step 4 wallet** at first, **Path A wallet** later — signing surface

## Layout (when populated)

```
step6-ap2-spike/
├── README.md                 (this file)
├── agent/                    (forked AP2 sample, custom agent role)
├── merchant/                 (the merchant role + VI verifier)
├── issuer/                   (tiny SD-JWT issuer for the demo)
└── facilitator/              (when we self-host an x402 facilitator)
```

Each subdirectory will have its own README documenting how to run that
piece in isolation and how the pieces compose end-to-end.
