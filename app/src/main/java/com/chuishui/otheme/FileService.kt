package com.chuishui.otheme

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile

class FileService(private val context: Context) : IFileService.Stub() {

    companion object {
        private const val TAG = "FileService"
        private const val USER_THEME_DIR = "/data/theme"
        private const val MODULE_DIR =
            "/data/adb/modules/otheme"
        private const val THEME_INNER_DIR =
            "/data/adb/modules/otheme/system_ext/media/themeInner"
        private const val BUFFER_SIZE = 8192
    }

    /**
     * 执行 root shell 命令（通过 su -c）
     * Shizuku 模式下以 root 身份运行，能访问 /data/adb 等系统目录
     */
    private fun execShellCommand(command: String): Pair<Int, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            Pair(exitCode, if (exitCode == 0) output else error)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "Unknown error")
        }
    }

    override fun destroy() {
        Log.d(TAG, "Service is being destroyed")
        System.exit(0)
    }

    override fun backupTheme(backupPath: String): String? {
        Log.d(TAG, "Backing up theme to: $backupPath")

        return try {
            val themeDir = File(USER_THEME_DIR)
            if (!themeDir.exists() || !themeDir.isDirectory) {
                val error = "Theme directory does not exist or is not a directory"
                Log.e(TAG, error)
                return error
            }

            val backupFile = File(backupPath)
            backupFile.parentFile?.mkdirs()

            // Use Apache Commons Compress to explicitly control filename encoding and unicode extra fields.
            FileOutputStream(backupFile).use { fos ->
                BufferedOutputStream(fos).use { bos ->
                    ZipArchiveOutputStream(bos).use { zaos ->
                        // Prefer UTF-8 names and include unicode extra fields for maximum compatibility
                        zaos.setEncoding("UTF-8")
                        zaos.setUseLanguageEncodingFlag(true)
                        zaos.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.ALWAYS)

                        // Recursively add files using URI relativize to avoid extra top-level directory
                        val baseUri = themeDir.toURI()
                        themeDir.walkTopDown().forEach { file ->
                            if (file == themeDir) return@forEach
                            val relPath = baseUri.relativize(file.toURI()).path.replace(File.separatorChar, '/')
                            if (relPath.isEmpty()) return@forEach
                            zipFileCommons(file, relPath, zaos)
                        }

                        zaos.finish()
                    }
                }
            }

            Log.d(TAG, "Theme backup completed successfully: ${backupFile.absolutePath} -> size=${backupFile.length()}")
            null // Success
        } catch (e: Exception) {
            val error = "Error backing up theme: ${e.message}"
            Log.e(TAG, error, e)
            error
        }
    }

    /**
     * 安装主题为 Magisk 模块
     *
     * 将 .theme 文件注入到 Magisk 模块 (/data/adb/modules/otheme)，
     * 通过 post-fs-data.sh 中的 mount --bind 绑定挂载来实现主题注入。
     * 兼容 Android 12+、EROFS、AVB 等。
     */
    override fun installTheme(themePath: String): String? {
        Log.d(TAG, "Installing theme module: $themePath")

        return try {
            val themeFile = File(themePath)

            if (!themeFile.exists() || !themeFile.isFile) {
                return "Theme file does not exist"
            }

            // 创建 Magisk 模块目录（通过 root shell 命令）
            execShellCommand("mkdir -p '$MODULE_DIR'")
            Log.d(TAG, "Module directory created/verified")

            // 删除 disable 标记文件（如果存在）
            execShellCommand("rm -f '$MODULE_DIR/disable'")
            Log.d(TAG, "Removed module disable marker")

            // 创建 module.prop
            val propFile = File(MODULE_DIR, "module.prop")
            propFile.writeText(
                """
id=otheme
name=OTheme - 注入系统主题附加模块
version=v2
versionCode=1
author=吹水明月
description=Injected by OTheme
""".trimIndent()
            )
            Log.d(TAG, "Created/Updated module.prop")

            // 创建 post-fs-data.sh 脚本
            val postFsDataFile = File(MODULE_DIR, "post-fs-data.sh")
            postFsDataFile.writeText(
                """#!/system/bin/sh
MODDIR=${"$"}{0%/*}
mount --bind ${"$"}MODDIR/system_ext/media/themeInner/ /system_ext/media/themeInner/
""".trimIndent()
            )
            execShellCommand("chmod 755 '$MODULE_DIR/post-fs-data.sh'")
            Log.d(TAG, "Created post-fs-data.sh with mount --bind")

            // 创建主题目录（通过 root shell 命令）
            execShellCommand("mkdir -p '$THEME_INNER_DIR'")
            Log.d(TAG, "Theme directory created/verified")

            // 只删除当前要安装的主题文件（避免误删其他主题）
            val targetPath = "$THEME_INNER_DIR/${themeFile.name}"
            execShellCommand("rm -f '$targetPath'")
            Log.d(TAG, "Removed existing theme file: ${themeFile.name}")

            // 复制主题文件到临时位置
            val tempPath = "/data/local/tmp/${themeFile.name}"
            themeFile.copyTo(File(tempPath), overwrite = true)
            Log.d(TAG, "Copied to temp: $tempPath")

            // 通过 root shell 复制到模块目录
            execShellCommand("cp '$tempPath' '$targetPath'")
            execShellCommand("chmod 644 '$targetPath'")
            Log.d(TAG, "Copied to module: $targetPath with permissions set")

            // 清理临时文件
            execShellCommand("rm -f '$tempPath'")

            // 关闭相关主题应用以刷新（支持多个 ColorOS/OxygenOS 变体）
            listOf(
                "com.heytap.themestore",
                "com.oplus.themestore",
                "com.coloros.themestore"
            ).forEach { pkg ->
                execShellCommand("am force-stop $pkg")
            }

            Log.d(TAG, "Theme installed to Magisk module: $targetPath")
            null

        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            "Install failed: ${e.message}"
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(BUFFER_SIZE)
            var r: Int
            while (fis.read(buf).also { r = it } > 0) {
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    override fun installThemeFromFd(pfd: ParcelFileDescriptor): String? {
        Log.d(TAG, "Installing theme from ParcelFileDescriptor (robust zip extraction)")

        val tempZip = File(context.cacheDir, "temp_service_install.theme.zip")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            FileOutputStream(tempZip).use { output ->
                input.copyTo(output)
            }
        }

        try {
            // Try to open with commons-compress and scan entries for .theme files
            val encodings = listOf("UTF-8", "GBK")
            var extractedAny = false
            encodings.forEach { enc ->
                try {
                    ZipFile(tempZip, enc).use { zf ->
                        val entries = zf.entries
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val name = entry.name.replace('\\', '/')
                            // Normalize name and look for .theme entries anywhere
                            if (name.endsWith(".theme", ignoreCase = true)) {
                                Log.d(TAG, "Found .theme entry in zip: $name (size=${entry.size})")
                                val outTemp =
                                    File(
                                        context.cacheDir,
                                        File(name).name
                                    )
                                zf.getInputStream(entry).use { ins ->
                                    FileOutputStream(outTemp).use { fos ->
                                        ins.copyTo(fos)
                                    }
                                }
                                // Verify size and checksum
                                Log.d(TAG, "Extracted ${outTemp.absolutePath} size=${outTemp.length()} sha256=${sha256(outTemp)}")
                                // Inject into Magisk module (reuse installTheme logic)
                                val res = installTheme(outTemp.absolutePath)
                                if (res != null) {
                                    Log.e(TAG, "installTheme returned error for ${outTemp.name}: $res")
                                } else {
                                    Log.d(TAG, "Injected theme file ${outTemp.name} successfully")
                                }
                                extractedAny = true
                                // optionally delete outTemp after injection
                                outTemp.delete()
                            }
                        }
                    }
                    if (extractedAny) return null // success, stop trying other encodings
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to open zip with encoding $enc: ${e.message}")
                }
            }

            // If no .theme entries found, maybe the uploaded file is already a .theme (not zip)
            if (!extractedAny) {
                Log.d(TAG, "No .theme entries found inside zip, trying raw copy as fallback")
                val fallbackTemp = File(context.cacheDir, "temp_service_install.theme")
                if (tempZip.copyTo(fallbackTemp, overwrite = true).exists()) {
                    Log.d(TAG, "Fallback copied raw file, size=${fallbackTemp.length()} sha256=${sha256(fallbackTemp)}")
                    val res = installTheme(fallbackTemp.absolutePath)
                    fallbackTemp.delete()
                    return res
                } else {
                    return "No .theme entries found in archive and fallback copy failed"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during robust install: ${e.message}", e)
            return "Error during installThemeFromFd: ${e.message}"
        } finally {
            tempZip.delete()
        }

        return null
    }

    override fun getThemeInfo(): List<String> {
        Log.d(TAG, "Getting theme info from Magisk module")

        return try {
            // 优先读取 Magisk 模块中的主题
            val themeDir = File(THEME_INNER_DIR)

            if (!themeDir.exists() || !themeDir.isDirectory) {
                Log.d(TAG, "Magisk theme directory does not exist, returning empty list")
                return emptyList()
            }

            val fileList = mutableListOf<String>()
            themeDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    fileList.add(file.absolutePath)
                }
            }

            Log.d(TAG, "Found ${fileList.size} theme files in Magisk module")
            fileList
        } catch (e: Exception) {
            Log.e(TAG, "Error getting theme info: ${e.message}", e)
            emptyList()
        }
    }

    override fun getInstalledThemeInfo(): String? {
        Log.d(TAG, "Getting installed theme info")

        return try {
            val themeInfoFile = File(USER_THEME_DIR, "themeInfo.xml")

            if (!themeInfoFile.exists() || !themeInfoFile.isFile) {
                Log.e(TAG, "themeInfo.xml does not exist")
                return null
            }

            val content = themeInfoFile.readText()
            Log.d(TAG, "Read themeInfo.xml, size: ${content.length} bytes")
            content
        } catch (e: Exception) {
            Log.e(TAG, "Error reading themeInfo.xml: ${e.message}", e)
            null
        }
    }

    override fun isPackageInstalled(packageName: String): Boolean {
        return try {
            Log.d(TAG, "Checking if package is installed: $packageName")

            // 方法1: pm path
            val process = Runtime.getRuntime().exec("pm path $packageName")
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.contains(packageName)) {
                Log.d(TAG, "Package $packageName found via pm path")
                return true
            }

            // 方法2: 兜底 — Android 原生 PackageManager API
            try {
                val info = context.packageManager.getPackageInfo(packageName, 0)
                Log.d(TAG, "Package $packageName found via PackageManager API, version: ${info.versionName}")
                return true
            } catch (_: PackageManager.NameNotFoundException) {
                Log.d(TAG, "Package $packageName not found via PackageManager API")
            }

            Log.d(TAG, "Package $packageName NOT installed")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking package installation: ${e.message}", e)
            // 异常时最后尝试原生 API
            try {
                val info = context.packageManager.getPackageInfo(packageName, 0)
                Log.d(TAG, "Package $packageName found via fallback PackageManager API")
                return true
            } catch (_: PackageManager.NameNotFoundException) { }
            false
        }
    }

    override fun restartProcesses(packages: List<String>): String? {
        Log.d(TAG, "===== Starting hot reboot =====")

        return try {
            val process = Runtime.getRuntime().exec("am restart")
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                Log.d(TAG, "Hot reboot triggered successfully")
                null
            } else {
                val error = "Hot reboot failed with exit code: $exitCode"
                Log.e(TAG, error)
                error
            }
        } catch (e: Exception) {
            val error = "Hot reboot failed: ${e.message}"
            Log.e(TAG, error, e)
            error
        }
    }

    override fun uninstallTheme(): String? {
        Log.d(TAG, "===== Uninstalling theme =====")

        return try {
            // 创建 remove 标记文件，让 Magisk 下次启动时删除模块
            execShellCommand("touch '$MODULE_DIR/remove'")
            Log.d(TAG, "Created remove marker for Magisk module")

            // 关闭主题应用以刷新
            listOf(
                "com.heytap.themestore",
                "com.oplus.themestore",
                "com.coloros.themestore"
            ).forEach { pkg ->
                execShellCommand("am force-stop $pkg")
            }

            Log.d(TAG, "Magisk theme module marked for removal")
            null

        } catch (e: Exception) {
            val error = "Uninstall failed: ${e.message}"
            Log.e(TAG, error, e)
            error
        }
    }

    private fun zipFile(file: File, zipPath: String, zos: ZipOutputStream) {
        if (file.isDirectory) {
            // 添加目录条目
            val entry = ZipEntry("$zipPath/")
            zos.putNextEntry(entry)
            zos.closeEntry()

            // 递归处理子文件
            file.listFiles()?.forEach { child ->
                zipFile(child, "$zipPath/${child.name}", zos)
            }
        } else {
            // 添加文件
            val entry = ZipEntry(zipPath)
            zos.putNextEntry(entry)
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(BUFFER_SIZE)
                var len: Int
                while (fis.read(buffer).also { len = it } > 0) {
                    zos.write(buffer, 0, len)
                }
            }
            zos.closeEntry()
        }
    }

    private fun zipFileCommons(file: File, zipPath: String, zaos: ZipArchiveOutputStream) {
        if (file.isDirectory) {
            val dirName = if (zipPath.endsWith("/")) zipPath else "$zipPath/"
            val entry = ZipArchiveEntry(dirName)
            zaos.putArchiveEntry(entry)
            zaos.closeArchiveEntry()

            file.listFiles()?.forEach { child ->
                val childRel = if (zipPath.isEmpty()) child.name else "$zipPath/${child.name}"
                zipFileCommons(child, childRel, zaos)
            }
        } else {
            // Create entry with the provided name and set size to improve compatibility
            val entry = ZipArchiveEntry(file, zipPath)
            entry.size = file.length()
            zaos.putArchiveEntry(entry)
            FileInputStream(file).use { fis ->
                fis.copyTo(zaos, BUFFER_SIZE)
            }
            zaos.closeArchiveEntry()
            Log.d(TAG, "Added zip entry: $zipPath (size=${file.length()})")
        }
    }
}
