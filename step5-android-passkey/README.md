# Step 5 — Path A Milestone 1 (passkey hello world)

Foundation for Path A: replace Step 4's secp256k1 seed (wrapped under a
StrongBox AES key) with a P-256 passkey held by Credential Manager. On
a Pixel with StrongBox the passkey lives in Titan M2 and never appears
in app RAM — eliminating the brief plaintext-seed window that's the
core weakness of Path B.

**M1 scope**: create one passkey, sign one 32-byte challenge, verify
the signature off-chain in Kotlin. No on-chain involvement, no smart
wallet, no x402 envelope. M2-M4 build on top.

See `../PATH_A_NEXT.md` for the full Path A plan and milestones M1-M4.

## Why Path A — beyond the RAM window

Closing the "seed sits in JVM heap during signing" window is real but
narrow: it only matters against an attacker who can run code in the
wallet process (root, debugger, memory-corruption exploit) during the
sign window. For most threats on a non-rooted Pixel, Step 4 and Step 5
both rely on the same StrongBox + biometric fence and are roughly
equivalent.

The bigger payoff is that **Path A unlocks the smart-wallet design
space**. Once payments settle through a smart-contract wallet that
verifies P-256 via EIP-1271 / EIP-7951 (M3), you get capabilities that
are structurally impossible with an EOA-only Step 4 wallet:

- **Multi-owner recovery.** A passkey on the Pixel, a passkey on a
  laptop, and a YubiKey can all be equivalent owners of the same wallet
  address. Lose one, the others still work. Step 4's seed has no
  recovery story — losing the device loses the funds. Detail in
  `../PATH_A_NEXT.md`'s "Recovery model" section.
- **Account abstraction features.** Paymasters (gasless transactions,
  third party covers gas in any token), batched ops (one biometric →
  approve + transfer in a single UserOp), spending limits and session
  keys, allowlists, time-locks. None of these compose with a plain EOA.
- **Upgradeable signature scheme.** Today P-256 via WebAuthn; tomorrow
  the same wallet address can add a post-quantum signer or a different
  curve as a second owner without rotating funds.
- **Social recovery modules.** M-of-N guardian recovery (Safe Recovery
  module pattern) for users who don't want hardware-key custody.

So the order of magnitude is: Step 4 is a hardware-backed **EOA
wallet**. Step 5/Path A is the foundation for a hardware-backed
**smart account**. Different design space, not just different storage.

The cost is real and worth naming: M3 needs a smart wallet contract
+ ERC-4337 bundler + a facilitator that accepts smart-wallet payments,
plus more on-chain attack surface and higher gas per payment.

## Three corrections to PATH_A_NEXT.md surfaced during M1 implementation

These are real and worth knowing about:

1. **`KeyInfo.getSecurityLevel()` does NOT work on passkey-managed keys.**
   Passkey credentials live in the system passkey provider's namespace
   (Google Password Manager backed by StrongBox), not in the app's
   AndroidKeyStore. `SecretKeyFactory.getKeySpec(..., KeyInfo::class.java)`
   isn't callable. M1 best-effort proxies via `FEATURE_STRONGBOX_KEYSTORE`
   and logs the inference explicitly. M2 will upgrade by requesting
   `attestation: "direct"` and parsing the attestation extension OID
   `1.3.6.1.4.1.11129.2.1.17` for the definitive security level.

2. **Drop keccak256 for M1.** PATH_A_NEXT.md mentions
   `keccak256("hello world")` but web3j is dropped in M1 (no longer
   needed without secp256k1). SHA-256 over "hello world" serves the
   round-trip test just as well. Keccak comes back in M2 for the
   smart-wallet UserOp hash.

3. **RP-ID is the registrable host (no path), served from
   `wellknown-host.vercel.app`.** Digital Asset Links binds passkeys to
   a registrable host, not a path. We host `assetlinks.json` on Vercel
   (see `wellknown-host/`). For production, register a subdomain you
   own and serve there instead.

4. **`requireResidentKey:true` is load-bearing on current GMS, despite
   being deprecated by WebAuthn L2.** Setting only `residentKey:"required"`
   returns a successful registrationResponseJson but the passkey never
   lands in GPM's queryable index — `getCredential` then fails with
   `NoCredentialException`. The deprecated `requireResidentKey` field
   is what makes GMS persist the credential. Google's own Android docs
   example sets both. Without this, days of debugging.

5. **`secp256k1` is rejected by AndroidKeyStore on Pixel 9 and 10.** No
   StrongBox, no TEE, no software level — the curve isn't in the
   supported set on Android 14+. This is why Path A is necessarily
   P-256 / passkey, not "wrap a secp256k1 key in StrongBox directly."
   See `SecpStrongboxProbe.kt` for the empirical proof.

## Files

| File | Purpose |
|---|---|
| `app/src/main/java/app/x402spike/PasskeyWallet.kt` | The passkey lifecycle: create, sign, parse responses |
| `app/src/main/java/app/x402spike/P256Verify.kt` | Off-chain WebAuthn assertion verifier (JCA-based) |
| `app/src/main/java/app/x402spike/MainActivity.kt` | Two-button UI: Create passkey, Sign test challenge |
| `app/src/main/java/app/x402spike/SecpStrongboxProbe.kt` | One-shot empirical check: AndroidKeyStore rejects secp256k1. Not run by default. |
| `app/src/test/java/app/x402spike/P256VerifyTest.kt` | JVM unit tests (no Android, no device) |
| `app/src/main/AndroidManifest.xml` | Asset Statements meta-data for RP-ID binding |
| `app/src/main/res/values/strings.xml` | RP-ID + asset_statements + button labels |
| `wellknown-host/` | Tiny Vercel project that serves `/.well-known/assetlinks.json` |

## Verification

### Autonomous (no device needed)

```sh
cd step5-android-passkey
./gradlew :app:assembleDebug                  # APK builds
./gradlew :app:testDebugUnitTest              # P256VerifyTest 6/6 pass
./gradlew :app:lintDebug                      # no lint errors
```

The unit tests cover: round-trip self-generated signature, tampered
authenticatorData, tampered clientDataJSON, DER decoding (32-byte r,
33-byte r with leading zero, short r left-padded).

### On-device (requires Pixel + biometric)

#### Pre-flight (once per fresh debug keystore)

1. Capture the SHA-256 fingerprint of your debug keystore:
   ```sh
   keytool -list -v -keystore ~/.android/debug.keystore \
     -alias androiddebugkey -storepass android -keypass android
   ```
2. Update `wellknown-host/.well-known/assetlinks.json` with that
   fingerprint, then redeploy:
   ```sh
   cd wellknown-host
   vercel deploy --prod --yes --scope <your-vercel-scope>
   ```
   The current production alias is `wellknown-host.vercel.app`. The
   `vercel.json` sets `Content-Type: application/json` explicitly.
3. Verify Google's Digital Asset Links API parses it:
   ```sh
   curl -s "https://digitalassetlinks.googleapis.com/v1/statements:list?\
   source.web.site=https://wellknown-host.vercel.app&\
   relation=delegate_permission/common.handle_all_urls"
   ```
   Should echo your package name + fingerprint back. This is the
   ground-truth check — the same API GMS calls.

#### Device pre-flight

1. **Set Google Password Manager as the system's preferred passkey
   provider.** On a fresh device, this is often unset.
   `adb shell settings get secure credential_service` should return
   `com.google.android.gms/com.google.android.gms.auth.api.credentials.credman.service.PasswordAndPasskeyService`.
   If empty:
   - Settings → Passwords, passkeys & accounts → **Preferred service** → **Google**
   - Or: Settings → Default apps → "Passwords, passkeys, and autofill"
2. Biometric enrolled (any).
3. Signed in to a Google account. (Multiple accounts is fine; GMS picks
   one for storage.)

#### Smoke run

```sh
./gradlew :app:installDebug
adb shell am start -n app.x402spike.passkey/app.x402spike.MainActivity
```

1. Tap **Create passkey** → system passkey UI fires, biometric prompt
2. Status shows the credentialId, the public key (x, y), and the
   security level (`STRONGBOX (inferred ...)` on a Pixel)
3. Logcat: `I/PasskeyWallet: passkey created: credentialId=... secLevel=STRONGBOX (inferred ...)`
4. Tap **Sign test challenge** → biometric prompt
5. Status shows `✓ off-chain P-256 verify passed` plus the assertion details
6. Logcat: `I/PasskeyWallet: passkey signed challenge with key: STRONGBOX (inferred ...)`

## Common failure modes

- **`NoCredentialException` on createPasskey** — `assetlinks.json`
  unreachable, wrong fingerprint, or wrong package name. Verify with
  the Digital Asset Links API curl above.
- **`CreateCredentialNoCreateOptionException` ("No create options
  available")** — no credential provider configured on the device.
  Settings → Default apps → Passwords, passkeys, and autofill → Google.
- **`CreateCredentialUnknownException`** — biometric not enrolled.
- **Create succeeds, Sign returns `NoCredentialException` ("Use another
  device")** — most common, two distinct subcauses:
  1. The request used `residentKey:"discouraged"` or didn't set
     `requireResidentKey:true`. The fix is in this codebase already.
  2. The device's local Cloud Key Vault / Folsom state is degraded:
     the Folsom flow runs only `GetSyncStatusOperation` and
     `GetKeyMaterialOperation` (reads) and never any
     `Store`/`Upsert` for the new credential. `ListCryptauthKeysOperation`
     returns size 0 even right after a successful create. **Fix:
     Settings → Apps → Google Play services → Storage & cache →
     Clear cache** (NOT Clear storage). This forces CKV
     re-enrollment without logging the user out. Confirmed reproduction
     and fix on Pixel 9 Pro XL with GMS 26.18.31.
- **`offline P-256 verify = false`** — usually means GPM picked a
  different passkey than the one whose pubkey we cached (e.g., multiple
  passkeys exist for the same RP from prior testing). Clear the app
  (`adb shell pm clear app.x402spike.passkey`) and re-create exactly
  once. Make sure `allowCredentials` is set to pin retrieval to your
  specific credentialId.
