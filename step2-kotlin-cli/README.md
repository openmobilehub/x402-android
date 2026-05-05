# Step 2 — Kotlin CLI x402 client

Same job as Step 1, on the JVM. One file (`src/main/kotlin/Main.kt`) that pays
a Base Sepolia x402 endpoint with USDC and prints the txHash. No SDK between us
and web3j — Step 2's whole point is to hit every JVM/web3j rock now, not on
Android in Step 3.

## Stack

- Kotlin 2.0.21, JDK 17, Gradle (wrapper at 8.10.2)
- `org.web3j:core` — `StructuredDataEncoder` for EIP-712, `Sign.signMessage` for ECDSA
- `com.squareup.okhttp3:okhttp` — HTTP (per CLAUDE.md, OkHttp over Ktor for now)
- `kotlinx-serialization-json` — challenge parsing, X-PAYMENT envelope build
- `dotenv-kotlin` — reads `step2-kotlin-cli/.env`, falls back to env var

No Android imports. No Spring. No KMP plugins. Plain JVM Kotlin so the protocol
logic lifts cleanly into `commonMain` if folded into a Kotlin Multiplatform
identity stack later.

## Setup

```sh
cd step2-kotlin-cli
cp .env.example .env
# paste the same PRIVATE_KEY from step1-node/.env (the address is already funded)
# OR generate a fresh one and re-faucet — your call
./gradlew build
```

First `./gradlew build` will pull web3j (~30 transitive deps) and take a minute.

## Run

```sh
./gradlew run --args="https://www.x402.org/protected"
```

Same demo endpoint as Step 1. Costs 0.01 USDC per request.

## What success looks like

```
payer:   0xEf9966c76afCa07798A9A65B619d897D77a6a0F9
network: eip155:84532 (Base Sepolia)
target:  https://www.x402.org/protected

status:  200
payment: {
  "success": true,
  "payer": "0xEf9966c76afCa07798A9A65B619d897D77a6a0F9",
  "transaction": "0x...",
  "network": "eip155:84532"
}

basescan: https://sepolia.basescan.org/tx/0x...

body:
<!DOCTYPE html>...
```

The txHash format must match Step 1 (32 bytes hex, 0x-prefixed). If it does and
BaseScan confirms a `transferWithAuthorization` from the Coinbase facilitator
moving USDC out of your address, Step 2 is done.

## What we deliberately did NOT do

- Did not pull `org.x402:x402:0.1.0-SNAPSHOT` — pre-release, not on Maven Central.
- Did not pull the Mogami Java client — also not on Maven Central as of today.
- Did not add KMP / Compose Multiplatform / iOS targets — explicit CLAUDE.md
  guard rail. The protocol code is KMP-friendly (no Android types, no JVM-only
  frameworks) but lives in a plain JVM module for now.

## Failure modes worth knowing (JVM-specific)

- **`SLF4J: No providers were found`** — should not happen; we depend on
  `slf4j-simple`. If you see it, the runtime classpath is broken.
- **`InvalidArgumentException` from `StructuredDataEncoder`** — the typed-data
  JSON we built is malformed. Print `typedData` before the encoder call and
  diff against the EIP-712 spec.
- **`signature invalid` from the facilitator** — the most likely cause is a
  domain-separator mismatch (wrong `name`, `version`, `chainId`, or
  `verifyingContract`). Confirm the values match what the server's `accepts`
  entry advertised.
- **`v` byte issues** — web3j's `Sign.signMessage(hash, keyPair, false)` returns
  v as 27 or 28 (one byte). If a downstream consumer expects 0/1, subtract 27.
  USDC's `transferWithAuthorization` accepts 27/28, so we ship as-is.
- **BouncyCastle conflicts on Android (later)** — irrelevant on JVM. We'll deal
  with it in Step 3 via ProGuard rules, per the research doc.
