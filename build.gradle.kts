// Top-level Gradle file for the ClickTrust Android SDK.
//
// We pin the Android Gradle Plugin + Kotlin versions in a single place so
// downstream `apply false` modules inherit the right toolchain without
// duplicating versions. Bumping either of these is a deliberate change.

plugins {
    id("com.android.library") version "8.2.2" apply false
    kotlin("android")             version "1.9.22" apply false
}
