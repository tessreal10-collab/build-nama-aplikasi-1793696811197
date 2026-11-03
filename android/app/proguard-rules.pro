# ══════════════════════════════════════════════════════════════
# APK TARGET v3.0 - PROGUARD RULES
# Anti-Reverse Engineering Configuration
# ══════════════════════════════════════════════════════════════

# ────────────────────────────────────────────────────────────────
# AGGRESSIVE OPTIMIZATION & OBFUSCATION
# ────────────────────────────────────────────────────────────────

# Enable maximum optimization passes
-optimizationpasses 7
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Aggressive obfuscation
-repackageclasses ''
-allowaccessmodification
-overloadaggressively
-mergeinterfacesaggressively

# Use dictionary for obfuscation (make names unreadable)
-classobfuscationdictionary obfuscation-dict.txt
-packageobfuscationdictionary obfuscation-dict.txt
-obfuscationdictionary obfuscation-dict.txt

# Remove all debug info
-keepattributes !SourceFile,!SourceDir,!LineNumberTable
-renamesourcefileattribute ""

# Keep annotations
-keepattributes *Annotation*

# ────────────────────────────────────────────────────────────────
# ANTI-HOOK & ANTI-FRIDA PROTECTION
# ────────────────────────────────────────────────────────────────

# Obfuscate reflection usage (makes hooking harder)
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Make all methods final where possible (prevents method hooking)
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

# ────────────────────────────────────────────────────────────────
# SECURITY: KEEP SECURITY MANAGER
# ────────────────────────────────────────────────────────────────

# Keep SecurityManager class and methods (for security checks)
-keep class com.sync.xxx.SecurityManager {
    public static <methods>;
}

-keep class com.sync.xxx.SecurityStatus {
    *;
}

# ────────────────────────────────────────────────────────────────
# KEEP ESSENTIAL CLASSES (BUT OBFUSCATE INTERNALS)
# ────────────────────────────────────────────────────────────────

# Keep MainActivity
-keep public class com.sync.xxx.MainActivity {
    public *;
}

# Keep DeviceService (must not be obfuscated for socket communication)
-keep class com.sync.xxx.DeviceService {
    public <methods>;
}

# Keep all Services
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Activity

# ────────────────────────────────────────────────────────────────
# ANDROID COMPONENTS
# ────────────────────────────────────────────────────────────────

# Keep view constructors
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep onClick methods
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# Keep Parcelable
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ────────────────────────────────────────────────────────────────
# KOTLIN
# ────────────────────────────────────────────────────────────────

-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# ────────────────────────────────────────────────────────────────
# SOCKET.IO
# ────────────────────────────────────────────────────────────────

-keep class io.socket.** { *; }
-keep class org.json.** { *; }
-dontwarn io.socket.**

# ────────────────────────────────────────────────────────────────
# CAMERAX
# ────────────────────────────────────────────────────────────────

-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ────────────────────────────────────────────────────────────────
# GOOGLE PLAY SERVICES
# ────────────────────────────────────────────────────────────────

-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ────────────────────────────────────────────────────────────────
# WEBVIEW
# ────────────────────────────────────────────────────────────────

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep JavaScript interface classes
-keepclassmembers class com.sync.xxx.MainActivity$WebAppInterface {
    public *;
}

# ────────────────────────────────────────────────────────────────
# REMOVE DEBUG & LOGGING (Anti-Analysis)
# ────────────────────────────────────────────────────────────────

# Remove all logging in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# Remove Kotlin intrinsics checks
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
    public static void throw*(...);
}

# Remove printStackTrace (hides stack trace from attackers)
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

# ────────────────────────────────────────────────────────────────
# ANTI-REVERSE ENGINEERING MEASURES
# ────────────────────────────────────────────────────────────────

# Remove source file names and line numbers (makes stack traces useless)
-renamesourcefileattribute ""

# Obfuscate native methods (makes JNI harder to reverse)
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ────────────────────────────────────────────────────────────────
# CONTROL FLOW OBFUSCATION
# ────────────────────────────────────────────────────────────────

# Enable control flow obfuscation (makes decompiled code harder to read)
-optimizations !code/simplification/cast,!field/*,!class/merging/*
-optimizations !code/allocation/variable

# ────────────────────────────────────────────────────────────────
# WARNINGS TO IGNORE
# ────────────────────────────────────────────────────────────────

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn okhttp3.**
-dontwarn okio.**
