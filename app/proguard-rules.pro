# Add project specific ProGuard rules here.

-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod
# 崩溃日志需要行号定位（配合 mapping.txt 还原）
-keepattributes SourceFile,LineNumberTable

# 枚举：R8 会混淆枚举名并"内联"裁剪未直接引用的常量
# （v2.5 实锤 REPEAT_ONE 被裁导致 release 单曲循环消失）
-keepclassmembers class * extends java.lang.Enum {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep enum com.carmusic.** { *; }

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# Gson + 项目 model（reflect 序列化）
-keep class com.google.gson.** { *; }
-keep class com.carmusic.source.model.** { *; }
-keep class com.carmusic.data.** { *; }

# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# 第三方 View（LrcView / Coil）
-keep class me.wcy.lrcview.** { *; }
-keep class coil.** { *; }
-keep class coil3.** { *; }

# Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# JavaMail（崩溃日志邮件导出）：mailcap handler 类靠字符串注册，R8 会当死代码删掉
-keep class com.sun.mail.** { *; }
-keep class com.sun.activation.** { *; }
-keep class javax.activation.** { *; }
-keep class javax.mail.** { *; }
-dontwarn com.sun.mail.**
-dontwarn com.sun.activation.**
-dontwarn javax.activation.**
-dontwarn javax.mail.**
