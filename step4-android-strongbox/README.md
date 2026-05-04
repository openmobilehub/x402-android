# Step 4 — Android app, StrongBox-wrapped seed (the demo)

The whole experiment lands here. Same UI as Step 3, same protocol code, same
on-chain settlement. The only thing that changes — and it changes everything
about the security story — is **where the seed lives between taps**.

## What's different from Step 3

| | Step 3 | Step 4 |
| --- | --- | --- |
| Seed origin | Hardcoded in `local.properties`, baked into `BuildConfig` at build time | Generated on-device on first launch via `SecureRandom` |
| Seed at rest | Sitting in the APK as a string constant | Encrypted with an AES-256-GCM key bound to StrongBox; ciphertext + IV in `SharedPreferences` |
| Seed in RAM | Lives in the JVM string pool for the entire process lifetime | Only inside the `signWithSeed { ... }` lambda, zeroed on exit |
| Auth | None | `BiometricPrompt.CryptoObject` per signing op, `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` so cached auth is impossible |
| Trust root | The phone's APK + the `.gitignore` on the repo | A discrete tamper-resistant chip (Titan M2 on Pixel) |

## What's the same

- AGP, Kotlin, JDK, Gradle versions
- web3j 4.12.2 — `StructuredDataEncoder`, `Sign.signMessage`, `Keys.toChecksumAddress`
- OkHttp client + EIP-3009 envelope shape
- "GET (no x402)" vs "Pay 0.01 USDC" comparison UI
- WebView render of the protected page

## Setup

1. Open `step4-android-strongbox/` in Android Studio.
2. **No `PRIVATE_KEY` to set this time.** Just let Studio populate
   `local.properties` with `sdk.dir`. Optionally override `DEMO_URL`.
3. Plug in the Pixel, USB debugging on, install + run.

## First launch

You'll see one big button: **"Create StrongBox wallet"**. Tapping it:

1. Generates an AES-256-GCM key in `AndroidKeyStore` aliased `x402_seed_wrap_key`,
   with `setIsStrongBoxBacked(true)`. On a Pixel 10 Pro that lands in Titan M2.
2. Runs `SecureRandom.nextBytes(ByteArray(32))` for the seed.
3. Triggers `BiometricPrompt` because the wrap key requires fresh biometric auth
   on every cipher init.
4. Inside the auth-bound `Cipher`, encrypts the seed → stores the ciphertext + IV
   in `SharedPreferences` (both safe to leak; they only decrypt with a fresh
   biometric).
5. Derives the address via web3j and caches it for the home-screen render.
6. Zeroes the seed `ByteArray`.
7. **Logs the security level** (`KeyInfo.getSecurityLevel()`) — must be `STRONGBOX`.
   If it falls back to `TRUSTED_ENVIRONMENT` or `SOFTWARE`, the status bar shows
   it and `adb logcat -s SecureWallet:I` will scream about it.

After this, the app shows the address and the GET / Pay buttons.

## Steady state (every payment)

Tap **Pay 0.01 USDC**:

1. App fetches the 402 challenge with no biometric (it's a public GET).
2. App parses the `accepts[]` array, picks `eip155:84532` + `exact`.
3. **`BiometricPrompt` fires** with `CryptoObject(cipher in DECRYPT_MODE)`.
4. On finger-success, the bound `Cipher` is usable for exactly one `doFinal` call.
5. Inside `signWithSeed { seed -> ... }`:
   - `ECKeyPair.create(BigInteger(1, seed))`
   - Build the EIP-3009 typed-data, sign via `Sign.signMessage`
   - Build the payment envelope JSON
6. Lambda returns. The seed `ByteArray` is zeroed in the `finally` block.
7. App POSTs the envelope, gets 200 + the protected page.

Logcat each tap:

```
SecureWallet I  signing with key: STRONGBOX
```

Anything other than `STRONGBOX` on a Pixel is a red flag — it means the OS
silently fell back. Common reasons: AES-GCM-on-StrongBox under load (rare on
Pixel 10 Pro), or `setIsStrongBoxBacked(true)` was ignored by the OEM.

## Reset

If you want to wipe and re-provision:

```sh
adb shell pm clear app.x402spike.strongbox
```

That clears `SharedPreferences` (which holds the ciphertext + IV) and removes
the AndroidKeyStore key referenced from this app, forcing the setup screen
again on next launch.

## Key spec, annotated

```kotlin
KeyGenParameterSpec.Builder(
    "x402_seed_wrap_key",
    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
    .setKeySize(256)
    .setUserAuthenticationRequired(true)
    // 0-second validity = every cipher op needs a fresh biometric.
    // CLAUDE.md flags > 0 as a footgun: cached auth defeats the purpose.
    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
    .setInvalidatedByBiometricEnrollment(true)
    .setUnlockedDeviceRequired(true)
    .setIsStrongBoxBacked(true) // gated on FEATURE_STRONGBOX_KEYSTORE
```

## Deferred follow-up (Step 4.1)

The per-wallet **Recharge** button currently copies the address to clipboard
and opens `faucet.circle.com`. Circle's faucet doesn't accept a query param
to pre-fill the address, so this is the cleanest manual flow available.

`CDP_FOLLOWUP.md` captures the plan to add a one-tap variant: when CDP
credentials are present in `local.properties`, the same button POSTs to
Coinbase Developer Platform's faucet API directly (ES256 JWT auth via
BouncyCastle, already on classpath). Falls back to the web flow on missing
config or API error. ~190 lines, separate commit, deliberately not in this
one so the Step 4 diff stays focused on the StrongBox + biometric story.

## What this still doesn't get you (and where Path A goes)

This is **Path B** from research.docx — encrypted-seed-wrap. It's what every
production Android wallet does today. The honest trade-off:

- The wrap key is in StrongBox and never leaves it.
- The seed is never persisted in plaintext.
- The seed *is* in process RAM during the signing op. A compromised app
  process or a kernel-level attacker can scrape it during that window.

Closing that last hole is the **Path A** weekend (passkey + ERC-4337 smart
wallet, P-256 key non-extractable from StrongBox forever, EIP-7951 precompile
verifies on-chain). Same `payX402` call site, different signer
implementation. Once Path A lands, the `signWithSeed { }` block disappears —
there is no seed to unwrap because the smart contract verifies a WebAuthn
assertion produced inside StrongBox without ever exposing the private key.

## Failure modes worth knowing

- **`KeyPermanentlyInvalidatedException`** — you re-enrolled biometrics. The
  wrap key is dead by design (`setInvalidatedByBiometricEnrollment(true)`).
  Run `adb shell pm clear app.x402spike.strongbox` and provision again.
- **`UserNotAuthenticatedException` outside a BiometricPrompt** — code path
  is calling `cipher.doFinal` without going through `BiometricPrompt`. This
  is a bug; the lambda must be invoked from the success callback.
- **`KeyInfo.getSecurityLevel() == TRUSTED_ENVIRONMENT` on a Pixel 10 Pro**
  — silent StrongBox fallback. Per CLAUDE.md, log it loudly, don't pretend.
  We log it in both setup and signing paths; the status bar surfaces it too.
- **`BiometricPrompt` shows but cancels with code 11** — no biometrics
  enrolled. Settings → Security → Fingerprint/Face Unlock → enroll one.
