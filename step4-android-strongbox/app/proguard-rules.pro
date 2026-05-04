# web3j + bouncycastle: keep classes accessed reflectively, ignore missing.
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.web3j.**
-keep class org.web3j.** { *; }

# SLF4J: we use the no-op binding; suppress dead static-binder warnings.
-dontwarn org.slf4j.**

# Coroutines + serialization rely on metadata that minify can strip otherwise.
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }

# Jackson (web3j transitive) reflective access.
-dontwarn com.fasterxml.jackson.**
-keep class com.fasterxml.jackson.** { *; }
