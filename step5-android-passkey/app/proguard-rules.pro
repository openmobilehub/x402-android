# androidx.credentials uses reflection across provider boundaries.
-keep class androidx.credentials.** { *; }
-dontwarn androidx.credentials.**

# kotlinx serialization
-keep class kotlinx.serialization.** { *; }

# CBOR library used to decode the WebAuthn attestationObject.
-keep class co.nstant.in.cbor.** { *; }
-dontwarn co.nstant.in.cbor.**

# Kotlin reflection (used implicitly by Credential Manager's response
# parsing on some Android versions).
-keep class kotlin.Metadata { *; }
