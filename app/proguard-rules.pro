# Keep all classes with native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# gomobile calls these implementations by their generated JNI method names.
# Renaming either the interfaces or Android callbacks silently disconnects
# PRISM policy, DNS statistics, and flow ownership in optimized APKs.
-keep class easypinative.** { *; }
-keep class * implements easypinative.PrismBlockObserver { *; }
-keep class * implements easypinative.PrismDomainPolicyResolver { *; }
-keep class * implements easypinative.PrismFlowOwnerResolver { *; }

# WorkManager creates its Room database implementation by generated class name.
# R8 full-mode method merging can fold that reflective construction into an
# unrelated class and make release builds crash before Application.onCreate.
-keep class androidx.work.** { *; }
-keep class androidx.room.** { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# Keep Tailscale classes for debuggability, but especially
# keep the classes with syspolicy MDM keys, some of which
# get used only by the Go backend. (The second rule is redundant,
# but explicit.)
-keep class com.tailscale.ipn.** { *; }
-keep class com.tailscale.ipn.mdm.** { *; }

# Keep specific classes from Tink library
-keep class com.google.crypto.tink.** { *; }

# Ignore warnings about missing Error Prone annotations
-dontwarn com.google.errorprone.annotations.**

# Keep Error Prone annotations if referenced
-keep class com.google.errorprone.annotations.** { *; }

# Keep Google HTTP Client classes
-keep class com.google.api.client.http.** { *; }
-dontwarn com.google.api.client.http.**

# Keep Joda-Time classes
-keep class org.joda.time.** { *; }
-dontwarn org.joda.time.**
