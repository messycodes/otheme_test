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
        private const val THEME_DIR = "/data/theme"
        private const val THEME_INNER_DIR = "/system_ext/media/themeInner"
        private const val BUFFER_SIZE = 8192
    }

    /**
     * 执行 shell 命令（Shizuku 模式下以 shell 身份运行）
     */
    private fun execShellCommand(command: String): Pair<Int, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
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
            val themeDir = File(THEME_DIR)
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
     * 安装主题（原样注入，不解包）
     *
     * 不再解包 .theme 文件到 /data/theme，而是将文件原样拷贝进系统内置
     * 主题目录 (/system_ext/media/themeInner)，由系统自动识别为内置主题模块。
     */
    override fun installTheme(themePath: String): String? {
        Log.d(TAG, "Installing theme (raw inject) from: $themePath")

        return try {
            val themeFile = File(themePath)

            if (!themeFile.exists() || !themeFile.isFile) {
                val error = "Theme file does not exist or is not a file"
                Log.e(TAG, error)
                return error
            }

            // system_ext 默认只读，安装前需重新挂载为可写
            execShellCommand("mount -o rw,remount /system_ext")

            val themeInnerDir = File(THEME_INNER_DIR)
            if (!themeInnerDir.exists() && !themeInnerDir.mkdirs()) {
                val error = "Failed to create $THEME_INNER_DIR"
                Log.e(TAG, error)
                return error
            }

            val targetFile = File(themeInnerDir, themeFile.name)
            themeFile.copyTo(targetFile, overwrite = true)

            execShellCommand("chmod 644 '${targetFile.absolutePath}'")
            execShellCommand("chown root:root '${targetFile.absolutePath}'")

            // 尝试恢复只读挂载状态（失败不影响安装结果）
            execShellCommand("mount -o ro,remount /system_ext")

            Log.d(TAG, "Theme injected into ${targetFile.absolutePath}")
            null
        } catch (e: Exception) {
            val error = "Error installing theme: ${e.message}"
            Log.e(TAG, error, e)
            e.printStackTrace()
            error
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
                                val outTemp = File(context.cacheDir, "extracted_${File(name).name}")
                                zf.getInputStream(entry).use { ins ->
                                    FileOutputStream(outTemp).use { fos ->
                                        ins.copyTo(fos)
                                    }
                                }
                                // Verify size and checksum
                                Log.d(TAG, "Extracted ${outTemp.absolutePath} size=${outTemp.length()} sha256=${sha256(outTemp)}")
                                // Inject into system_ext (reuse installTheme logic)
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
        Log.d(TAG, "Getting theme info")

        return try {
            val themeDir = File(THEME_DIR)

            if (!themeDir.exists() || !themeDir.isDirectory) {
                Log.e(TAG, "Theme directory does not exist")
                return emptyList()
            }

            val fileList = mutableListOf<String>()
            themeDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    fileList.add(file.absolutePath)
                }
            }

            Log.d(TAG, "Found ${fileList.size} files in theme directory")
            fileList
        } catch (e: Exception) {
            Log.e(TAG, "Error getting theme info: ${e.message}", e)
            emptyList()
        }
    }

    override fun getInstalledThemeInfo(): String? {
        Log.d(TAG, "Getting installed theme info")

        return try {
            val themeInfoFile = File(THEME_DIR, "themeInfo.xml")

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
            val dir = File(THEME_DIR)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.filter { it.name != "config" && it.name != "applying" }?.forEach {
                    val deleted = it.deleteRecursively()
                    Log.d(TAG, "Deleted ${it.absolutePath}: $deleted")
                }
            }
            // 确保 applying 目录存在
            val applyingDir = File(THEME_DIR, "applying")
            if (!applyingDir.exists()) {
                applyingDir.mkdirs()
            }
            Log.d(TAG, "Theme uninstalled successfully (config and applying preserved)")
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
