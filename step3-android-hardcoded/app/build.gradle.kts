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
val privateKey: String = localProperties.getProperty("PRIVATE_KEY", "")
val demoUrl: String = localProperties.getProperty("DEMO_URL", "https://www.x402.org/protected")

android {
    namespace = "app.x402spike"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.x402spike"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"

        // The whole point of Step 3 is "key in BuildConfig, no StrongBox yet."
        // Step 4 replaces these fields with a StrongBox-wrapped seed.
        buildConfigField("String", "PRIVATE_KEY", "\"$privateKey\"")
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

    // web3j drags in netty + jackson + bouncycastle, which collide with Android
    // packaging defaults. These excludes keep the APK build happy.
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

    implementation("org.web3j:core:4.12.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Silence SLF4J's "no provider" log spam without bringing in a real logger.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.13")
}
