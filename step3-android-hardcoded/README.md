# Step 3 — Android app, hardcoded key in BuildConfig

Same payment as Steps 1 and 2 — but on your Pixel, triggered by tapping a
button. Single screen, one button, no biometrics yet, no StrongBox yet.
The whole point of this step is to prove the network plumbing, deps, and
ProGuard work on a real device. **Step 4 is where StrongBox lands.**

## Stack

- AGP 8.7.0, Kotlin 2.0.21, JDK 17, Gradle 8.10.2 wrapper
- minSdk 28 (StrongBox API requires 28+, kept consistent for Step 4)
- targetSdk 35
- AppCompat + ConstraintLayout (no Compose — this is a one-screen demo)
- web3j 4.12.2 — `StructuredDataEncoder` + `Sign.signMessage`, lifted from Step 2
- OkHttp 4.12, kotlinx-serialization-json, kotlinx-coroutines-android

The `X402Client` object in `app/src/main/java/app/x402spike/X402Client.kt`
is the protocol code from Step 2 with two surface changes:
network call wrapped in `withContext(Dispatchers.IO)`, errors returned as
`PayResult.Failure` instead of `System.exit(...)`.

## Setup

1. **Open in Android Studio.**
   ```
   File → Open → step3-android-hardcoded
   ```
   First open will sync Gradle. Studio will write `sdk.dir` into
   `local.properties` automatically.

2. **Add your test private key** to `step3-android-hardcoded/local.properties`:
   ```properties
   PRIVATE_KEY=0xYourTestKey
   ```
   Reuse the same key from Step 1 / Step 2 — that address is already funded
   with Base Sepolia USDC. `local.properties` is gitignored at the repo root.

3. **Connect your Pixel.** USB debugging on, "Allow" the prompt.

4. **Run** (Studio's green play button, or `./gradlew :app:installDebug`
   then launch from the device's app drawer).

## What success looks like

- App launches showing your address (checksummed) and the demo URL.
- Tap **Pay 0.01 USDC**.
- Within 1–2 seconds, the status text fills in:
  ```
  OK status 200

  txHash:
  0x...

  basescan:
  https://sepolia.basescan.org/tx/0x...
  ```
- Click the basescan link and confirm a `transferWithAuthorization` from
  the Coinbase facilitator's address moving 0.01 USDC out of your wallet.

## Things deliberately not done in Step 3

- No biometric prompt
- No StrongBox / AndroidKeystore code anywhere
- No screen for entering the URL — hardcoded via BuildConfig
- No release build / minify verification — see `proguard-rules.pro` for
  the rules, but debug install is enough to call Step 3 done

Step 4 keeps everything in this module identical except for one thing:
the `BuildConfig.PRIVATE_KEY` line in `MainActivity` becomes a
StrongBox-unwrap path gated by `BiometricPrompt`.

## Failure modes worth knowing (Android-specific)

- **`MissingPrivateKey` on launch** — `local.properties` doesn't have
  `PRIVATE_KEY=`, or the build was cached before you set it. In Studio:
  `Build → Clean Project`, then re-run.
- **`SecurityException: cleartext HTTP not permitted`** — irrelevant here,
  we only hit HTTPS endpoints. If you swap in a localhost test server
  later, add a network security config.
- **`NoSuchProviderException: BC` / Bouncy Castle weirdness** — Android's
  bundled BC is older than web3j's. We don't `Security.addProvider(...)`
  and we don't ask for the BC provider by name; web3j-crypto uses its
  bundled implementation. If you see this, something pulled BC by name
  somewhere. Check the stack trace.
- **`ClassNotFoundException` after enabling minify** — your release-build
  ProGuard rules are too aggressive. Add a `-keep class your.package.**`
  rule until the missing class shows up, then narrow it.
- **App freezes on tap** — the network call ran on the main thread.
  Verify `X402Client.payX402` is invoked from `lifecycleScope.launch {}`
  and that `payX402` itself stays on `Dispatchers.IO`.
