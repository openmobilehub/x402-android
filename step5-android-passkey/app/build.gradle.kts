plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.x402spike"
    compileSdk = 35

    defaultConfig {
        // Distinct from step4's app.x402spike.strongbox so both apps install
        // side by side on the same device for the eventual M4 comparison demo.
        applicationId = "app.x402spike.passkey"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/DISCLAIMER",
                "META-INF/NOTICE",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/LICENSE",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // Path A's foundation: Credential Manager handles passkey creation +
    // assertion via the system passkey UI. The play-services-auth artifact
    // provides the Google Password Manager backend on devices that need an
    // explicit provider (mostly < API 34, but useful to have everywhere for
    // consistent behavior across test devices).
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Used once by PasskeyWallet.parseRegistrationResponse to walk the
    // attestationObject CBOR and extract the COSE-encoded P-256 public key.
    // Bouncy Castle's CBOR is heavier; this is single-purpose and small.
    implementation("co.nstant.in:cbor:0.9")

    // Off-chain P-256 verifier tests run on plain JVM, no Android dependencies.
    testImplementation("junit:junit:4.13.2")
}
