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

                        // Recursively add files
                        val basePath = themeDir.toPath()
                        themeDir.walkTopDown().forEach { file ->
                            if (file == themeDir) return@forEach
                            val relPath = basePath.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                            if (relPath.isEmpty()) return@forEach
                            zipFileCommons(file, relPath, zaos)
                        }

                        // Ensure everything is written
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
                        for (entry in entries) {
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

        ... (truncated for brevity)
