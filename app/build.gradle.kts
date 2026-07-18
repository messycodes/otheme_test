diff --git a/app/build.gradle.kts b/app/build.gradle.kts
index 6a529ab..0000000 100644
--- a/app/build.gradle.kts
+++ b/app/build.gradle.kts
@@
     // Apache Commons Compress: explicit ZIP encoding control and robust ZIP handling
     implementation("org.apache.commons:commons-compress:1.23.0")
+
+    // Provide compileOnly stubs for optional native/extra compression backends so R8 can resolve references
+    compileOnly("com.github.luben:zstd-jni:1.5.2-4")
+    compileOnly("org.brotli:dec:0.1.2")
+    compileOnly("org.ow2.asm:asm:9.5")
+    compileOnly("org.tukaani:xz:1.9")
@@
 }
