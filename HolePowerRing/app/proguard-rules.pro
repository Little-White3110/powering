# Xposed 模块入口必须保留
-keep class com.powerring.hole.ModuleEntry { *; }
-keep class com.powerring.hole.hook.** { *; }
-keep class com.powerring.hole.ring.** { *; }
-keep class com.powerring.hole.data.** { *; }
-dontwarn de.robv.android.xposed.**
