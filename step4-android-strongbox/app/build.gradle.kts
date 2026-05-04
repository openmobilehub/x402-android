import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val demoUrl: String = localProperties.getProperty("DEMO_URL", "https://www.x402.org/protected")

android {
    namespace = "app.x402spike"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.x402spike.strongbox"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"

        // No PRIVATE_KEY here on purpose — Step 4 generates one inside StrongBox.
        buildConfigField("String", "DEMO_URL", "\"$demoUrl\"")
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
        buildConfig = true
        viewBinding = true
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

    // Biometric prompt — the whole point of Step 4. 1.1.0 is the stable line and
    // gives us BiometricPrompt.CryptoObject for binding the cipher to auth.
    implementation("androidx.biometric:biometric:1.1.0")

    implementation("org.web3j:core:4.12.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    runtimeOnly("org.slf4j:slf4j-nop:2.0.13")
}
