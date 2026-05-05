// ClickTrust Android SDK module.
//
// Distribution targets (in priority order):
//   1. Gradle dependency via Maven Central — `cc.clicktrust:clicktrust-sdk:<ver>`
//   2. Gradle dependency via JitPack as a fallback during pre-release
//   3. AAR drop-in for partners on legacy build pipelines
//
// Hard requirements:
//   - Min API 24 (Android 7.0). HMAC-SHA256 via javax.crypto and the
//     Activity / Application lifecycle hooks we rely on are all
//     available from 24+ without compatibility shims.
//   - Compile + target the latest stable SDK so we get the newest
//     ConnectivityManager + privacy APIs (we still gate calls on
//     Build.VERSION.SDK_INT for anything API-restricted).
//   - Zero third-party runtime deps. Apps integrating the SDK
//     should not inherit OkHttp / Gson / etc.

plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

android {
    namespace = "cc.clicktrust.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        // The SDK does not bundle UI resources, but Android Gradle Plugin
        // still demands a target SDK so lint can size up uses-permission
        // declarations correctly.
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            // Release artifacts ship to Maven; consumers run their own
            // R8 / ProGuard. Our consumer-rules.pro already keeps every
            // public symbol so they don't need to whitelist us.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        // -Xexplicit-api would force every public symbol to declare
        // visibility — we already do that by hand, but turn it on at
        // the strict level once the API stabilises.
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Kotlin stdlib — the only runtime dependency. Coroutines are NOT
    // pulled in; we use plain `Thread`s and a single `Handler` for the
    // few main-thread hops the captcha overlay needs. Keeps the AAR
    // ~25 KiB instead of ~1.5 MiB.
    implementation("androidx.annotation:annotation:1.7.1")

    // Instrumented + JVM unit tests — never shipped to consumers.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "cc.clicktrust"
            artifactId = "clicktrust-sdk"
            version = "1.0.0"
            // Wire the AAR component once Android Gradle finishes
            // configuration. `release` is created lazily by the Android
            // plugin so we attach it inside afterEvaluate.
            afterEvaluate {
                from(components["release"])
            }
            pom {
                name.set("ClickTrust Android SDK")
                description.set("Native Android SDK for ClickTrust fraud detection — same signal collection + verdict + captcha protocol as the JS snippet and the iOS SDK.")
                url.set("https://github.com/bdeeps/ClickTrust-Sentinel")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
