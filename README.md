# ClickTrust Android SDK

Native Android SDK for [ClickTrust](https://app.clicktrust.cc) fraud
detection. Mirrors the iOS SDK and the JS snippet so a single account
configuration works the same regardless of where the click originates.

- Min Android: API 24 (Android 7.0)
- Languages: Kotlin (Java-friendly public API)
- Runtime size: ~30 KiB AAR, **no third-party dependencies** at runtime
- Threading: zero work on the main thread beyond the captcha overlay

## What it does

1. Sends a signed `POST /api/collect` on cold start, every foreground,
   and on demand. The server returns a `Verdict` (`allow` /
   `challenge` / `block` / `shadow`) the SDK can act on.
2. Buffers session-replay events (`tap`, `scroll`, `screen_view`,
   `orient`, `foreground`, `background`) and posts them in batches.
3. Presents a captcha overlay (PoW + colour tap target) when the
   server returns `challenge`.
4. Runs the same anti-detect checks as the JS snippet (root, Frida /
   Xposed, debugger, emulator, repackaged-APK detection).

## Install

### Gradle (Maven Central)

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("cc.clicktrust:clicktrust-sdk:1.0.0")
}
```

### Gradle (JitPack fallback)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.bdeeps:ClickTrust-Sentinel:1.0.0")
}
```

### AAR drop-in

Download `clicktrust-sdk-1.0.0.aar` from the GitHub release and copy
into `app/libs`, then:

```kotlin
dependencies {
    implementation(files("libs/clicktrust-sdk-1.0.0.aar"))
}
```

## Configure

Initialise once, in your `Application.onCreate()`:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ClickTrust.configure(
            application = this,
            config = ClickTrustConfig(
                trackingId = BuildConfig.CLICKTRUST_TID,
                apiBase = "https://app.clicktrust.cc",
                sdkSecret = BuildConfig.CLICKTRUST_SDK_SECRET,
            ),
        )

        ClickTrust.onVerdict { verdict ->
            when (verdict.action) {
                Verdict.Action.BLOCK -> {
                    // Hide your offer / paywall, log analytics
                }
                Verdict.Action.CHALLENGE -> {
                    // SDK auto-presents the captcha overlay; you can
                    // additionally dim the underlying view here
                }
                else -> Unit
            }
        }
    }
}
```

### Where do `trackingId` and `sdkSecret` come from?

In the ClickTrust dashboard:

1. **Accounts → New** → pick **Android** as the platform.
2. Enter your `applicationId` (must match `BuildConfig.APPLICATION_ID`).
3. Save — you get a one-time `sdkSecret` shown on screen.
4. Paste it into `local.properties` and reference it via
   `BuildConfig.CLICKTRUST_SDK_SECRET`. Never commit the secret to
   source control. Rotate from the same screen if it leaks.

The `apiBase` is your dashboard URL — typically
`https://app.clicktrust.cc`.

## Manifest permissions

The SDK declares only:

- `INTERNET` — required for collect / session events
- `ACCESS_NETWORK_STATE` — drives the Wi-Fi vs cellular signal

Manifest merger handles both for you. Add nothing else.

## ProGuard / R8

`consumer-rules.pro` is bundled and keeps every public symbol the
public API exposes. Nothing to do.

## Sensitive views

```kotlin
// Mark a credit-card field so future screenshot-based recording skips it.
ClickTrust.maskField(myCreditCardEditText)
```

## Force a collect

```kotlin
// Run after the user navigates to a high-value screen, hits a
// payment button, etc. Throttled to one collect per 5s.
ClickTrust.collectNow()
```

## Repackaged-APK detection

Pass your production signing certificate's lowercase SHA-256 hex to
turn on `resignedBundle` detection:

```kotlin
ClickTrust.configure(
    application = this,
    config = ClickTrustConfig(/* ... */),
    expectedSigningCertSha256 = BuildConfig.CLICKTRUST_PROD_SIGNING_SHA256,
)
```

Find your prod cert hash with:

```sh
apksigner verify --print-certs my-release.apk \
  | grep "SHA-256" | head -1 | awk '{print tolower($NF)}'
```

## Wire protocol notes

- `POST /api/collect` body is **JSON UTF-8**.
- HMAC headers: `X-CT-Signature: sha256=<hex>`, `X-CT-Timestamp: <ms>`.
- Canonical signed form: `"${ts}." + <raw body bytes>`.
- 5-minute server-side replay window.

The Kotlin signer in `transport/HmacSigner.kt` produces byte-identical
signatures with the iOS `HMACSigner.swift`. Tested against the same
fixtures.

## Building from source

```sh
cd sdks/android
./gradlew :clicktrust-sdk:assembleRelease
./gradlew :clicktrust-sdk:test
```

The release AAR lands at
`clicktrust-sdk/build/outputs/aar/clicktrust-sdk-release.aar`.
