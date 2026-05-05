# R8 rules applied when this module is itself minified (we don't
# minify in `release` — see build.gradle.kts — but keep these so the
# rules ride along if a downstream wrapper enables minification).

-keepattributes *Annotation*
-keepattributes Signature
-keep class cc.clicktrust.sdk.** { *; }
