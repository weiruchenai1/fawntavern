# Add project specific ProGuard rules here.
-dontwarn com.tencent.bugly.**
-keep public class com.tencent.bugly.** { *; }

# quickjs-kt：JNI 入口类（静态注册符号 + 反射绑定类），防混淆打断符号查找
-keep class com.dokar.quickjs.** { *; }
