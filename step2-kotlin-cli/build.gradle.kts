plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // No x402 SDK on Maven Central yet (org.x402 is 0.1.0-SNAPSHOT, mogami isn't published).
    // Step 2's stated goal is to hit web3j directly, so we use it directly.
    implementation("org.web3j:core:4.12.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")

    // web3j logs through SLF4J. Without a binding you get noisy "no provider" warnings.
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("MainKt")
}

tasks.register<JavaExec>("runSigTest") {
    group = "application"
    description = "Sign a fixed EIP-712 message with the same inputs as step1-node/sigtest.js"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("SigTestKt")
}

// Pass-through for run --args so we can do: ./gradlew run --args="https://..."
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
