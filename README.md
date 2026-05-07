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
    implementation("cc.clicktrust:clicktrust-sdk:1.1.0")
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
    implementation("com.github.clicktrust-sdk:android:1.1.0")
}
```

### AAR drop-in

Download `clicktrust-sdk-1.1.0.aar` from the GitHub release and copy
into `app/libs`, then:

```kotlin
dependencies {
    implementation(files("libs/clicktrust-sdk-1.1.0.aar"))
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

## App-level events (`trackEvent`)

App events are funnel-level user actions captured in addition to
fraud-detection telemetry — install, signup, purchase, level_complete,
and any custom event your app cares about. Each event is linked
server-side to the most recent fraud-scored click for the same device,
so the dashboard can answer "did this $99 purchase come from a CLEAN
session?".

The taxonomy mirrors AppsFlyer / Adjust / Firebase Analytics — use
the constants in `AppEvent.Names` whenever you can so events roll up
into the pre-built dashboards. Custom names still record but are
categorised as `"custom"`.

### Auto-events (no code required)

`ClickTrust.configure(...)` automatically fires these four events:

- `install` — once per device, on the first SDK launch after install
- `first_open` — same trigger as install (kept distinct for AppsFlyer
  parity — some networks subscribe to one but not the other)
- `session_start` — on every cold-start, and on every foreground
  re-entry after a 30-second idle window
- `update` — when the SDK detects a version-name change since the
  last launch

To disable auto-events (e.g. you have a homegrown analytics layer):

```kotlin
ClickTrust.configure(application = this, config = cfg, autoEvents = false)
```

### Manual events

```kotlin
import cc.clicktrust.sdk.ClickTrust
import cc.clicktrust.sdk.models.AppEvent

// Standard event with revenue
ClickTrust.trackEvent(
    name = AppEvent.PURCHASE,
    amount = 9.99,
    currency = "USD",            // optional; account default is used otherwise
    contentId = "sku_42",
    contentType = "product",
    quantity = 1,
    properties = mapOf("orderId" to "ord_123", "promo" to "WELCOME10"),
)

// Engagement event
ClickTrust.trackEvent(name = AppEvent.LOGIN, properties = mapOf("method" to "google"))

// Custom event (not in the standard catalog — still recorded)
ClickTrust.trackEvent(name = "promo_redeem_v3", properties = mapOf("variant" to "B"))
```

### Idempotency

Each call gets an auto-generated `externalId` (UUID) so a network
retry never produces a duplicate. Pass your own when you want to
deduplicate against a server-side order id:

```kotlin
ClickTrust.trackEvent(
    name = AppEvent.PURCHASE,
    amount = 9.99,
    externalId = "ord_${order.id}",   // server upserts on (account_id, external_id)
)
```

### Standard event catalog

Lifecycle (auto): `install`, `first_open`, `session_start`, `update`
Engagement: `login`, `signup`, `tutorial_complete`, `search`, `share`, `rate`
Ecommerce: `view_item`, `add_to_cart`, `add_to_wishlist`, `begin_checkout`, `add_payment_info`, `purchase`, `refund`, `subscribe`, `trial_start`
Ads: `ad_view`, `ad_click`, `ad_reward`
Gaming: `level_start`, `level_complete`, `achievement_unlocked`, `spend_credits`
Content: `content_view`, `content_complete`

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
