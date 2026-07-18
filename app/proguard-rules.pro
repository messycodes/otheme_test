# Apache Commons Compress 保留，防止 R8/ProGuard 混淆导致运行时缺失
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
