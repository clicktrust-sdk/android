# Changelog

## 1.1.0

App-level event tracking.

* New `ClickTrust.trackEvent(name, ...)` method for funnel-level events
  (install, signup, purchase, level_complete, custom).
* Auto-events fired by `configure()` (opt-out via `autoEvents = false`):
  `install`, `first_open`, `session_start`, `update`. AppsFlyer-equivalent
  defaults so partners get install metrics with zero extra code.
* New `cc.clicktrust.sdk.models.AppEvent.Names` constants matching the
  standard event catalog (lifecycle / engagement / ecommerce / ads /
  gaming / content).
* New `transport.AppEventClient` — buffered, batched (50/req, 5s flush),
  HMAC-signed POSTs to `/api/app-events`.
* Default revenue currency is USD when `amount` is set without `currency`
  (account-level override coming).
* Idempotency: every call gets an auto-generated `externalId` (or pass
  your own for server-side dedupe against an order id).

## 1.0.0

Initial release.

* `ClickTrust.configure(...)` / `collectNow()` / `onVerdict(...)`.
* HMAC-signed `/api/collect` + `/api/session-events`.
* Captcha overlay (PoW + colour-tap target).
* Anti-detect: rooted, emulator, debugger, Frida / Xposed, repackaged-APK.
* Lifecycle observer: cold-start + foreground collects, orientation
  signals.
