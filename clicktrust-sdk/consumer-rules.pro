# R8 / ProGuard rules forwarded to consumers when they minify their
# release builds. Every public surface of the SDK must survive
# minification because callers identify symbols by exact name (Kotlin
# top-level functions, the `ClickTrust` companion, captcha overlay
# resource classes).

-keep class cc.clicktrust.sdk.ClickTrust { *; }
-keep class cc.clicktrust.sdk.ClickTrustConfig { *; }
-keep class cc.clicktrust.sdk.models.** { *; }
-keep class cc.clicktrust.sdk.block.** { *; }

# Keep enum entries — Verdict.action is serialized by name and a
# stripped enum would round-trip to "0" / "1" instead of "block" /
# "challenge".
-keepclassmembers enum cc.clicktrust.sdk.** { *; }
