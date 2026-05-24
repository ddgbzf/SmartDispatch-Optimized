# Add project specific ProGuard rules here.
-keep class org.apache.poi.** { *; }
-keep class com.example.smartdispatch.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.osgi.** { *; }
-keep class org.w3c.dom.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.osgi.**
-dontwarn org.w3c.dom.**
