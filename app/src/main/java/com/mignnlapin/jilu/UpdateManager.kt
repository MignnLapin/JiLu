package com.mignnlapin.jilu

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查 GitHub Release 更新。优先使用官方 API，被限流时改用发布页 / Atom。
 * 发现新版本后可直接下载安装包并拉起系统安装器。
 */
object UpdateManager {
    private const val REPO = "MignnLapin/JiLu"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val WEB_LATEST = "https://github.com/$REPO/releases/latest"
    private const val WEB_RELEASES = "https://github.com/$REPO/releases"
    private const val ATOM = "https://github.com/$REPO/releases.atom"
    private const val USER_AGENT = "JiLu"
    private val MIRRORS = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://mirror.ghproxy.com/"
    )

    @Volatile
    var lastError: String? = null
        private set

    data class Info(val version: String, val url: String, val notes: String)

    enum class InstallResult { OPENED, NEED_PERMISSION, FAILED }

    suspend fun checkForUpdate(current: String): Info? = withContext(Dispatchers.IO) {
        lastError = null
        val viaApi = checkViaApi(current)
        if (viaApi != null) return@withContext viaApi
        if (lastError == null) return@withContext null

        val apiError = lastError
        lastError = null
        val viaWeb = checkViaWeb(current)
        if (viaWeb != null) {
            lastError = null
            return@withContext viaWeb
        }
        if (lastError == null) {
            null
        } else {
            lastError = "接口：$apiError；网页：${lastError}"
            null
        }
    }

    private fun checkViaApi(current: String): Info? {
        var attempt = 0
        while (attempt < 2) {
            attempt++
            try {
                val connection = URL(API_LATEST).openConnection() as HttpURLConnection
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                val code = connection.responseCode
                val body = if (code in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
                connection.disconnect()
                if (code !in 200..299) {
                    lastError = if (code == 403) {
                        "GitHub API 访问受限（HTTP 403）"
                    } else {
                        "HTTP $code"
                    }
                    return null
                }
                val json = JSONObject(body)
                val version = json.optString("tag_name").removePrefix("v").trim()
                if (version.isEmpty()) {
                    lastError = "发布信息缺少版本号"
                    return null
                }
                if (compareVersion(version, current) <= 0) return null

                var url = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                            url = asset.optString("browser_download_url")
                            break
                        }
                    }
                }
                if (url.isEmpty()) url = json.optString("html_url")
                if (url.isEmpty()) {
                    lastError = "该版本没有可下载的 APK"
                    return null
                }
                return Info(version, url, json.optString("body"))
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
            }
        }
        return null
    }

    private fun checkViaWeb(current: String): Info? {
        val tag = latestTagFromRedirect() ?: latestTagFromAtom() ?: latestTagFromHtml()
        if (tag == null) {
            lastError = "无法访问 github.com 发布页"
            return null
        }
        val version = tag.removePrefix("v").trim()
        if (version.isEmpty()) {
            lastError = "解析到的版本号为空"
            return null
        }
        if (compareVersion(version, current) <= 0) return null
        return Info(version, findApkUrl(tag, version), "")
    }

    private fun latestTagFromRedirect(): String? = try {
        val connection = URL(WEB_LATEST).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        val code = connection.responseCode
        val location = connection.getHeaderField("Location")
        val body = if (code == 200) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            null
        }
        connection.disconnect()
        var tag: String? = null
        if (code in 300..399 && location != null) {
            tag = location.substringAfter("/releases/tag/", "")
                .trim('/')
                .takeIf { it.isNotEmpty() && !it.contains("?") }
        }
        if (tag == null && body != null) {
            tag = Regex("\"tagName\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?: Regex("/releases/tag/([^\"<>/?]+)").find(body)?.groupValues?.get(1)
        }
        tag
    } catch (t: Throwable) {
        null
    }

    private fun latestTagFromAtom(): String? {
        val body = httpGet(ATOM) ?: return null
        return Regex("/releases/tag/([^\"<>/]+)").find(body)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun latestTagFromHtml(): String? {
        val body = httpGet(WEB_RELEASES) ?: return null
        return Regex("/releases/tag/([^\"<>/?]+)").find(body)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun findApkUrl(tag: String, version: String): String {
        val html = httpGet("https://github.com/$REPO/releases/expanded_assets/$tag")
        if (html != null) {
            Regex("href=\"([^\"]+\\.apk)\"", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.let { href ->
                    return if (href.startsWith("http")) href else "https://github.com$href"
                }
        }
        return "https://github.com/$REPO/releases/download/$tag/JiLu-$version.apk"
    }

    private fun httpGet(url: String): String? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        val code = connection.responseCode
        val body = if (code in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            null
        }
        connection.disconnect()
        body
    } catch (t: Throwable) {
        null
    }

    /** 下载 APK 到应用缓存目录；GitHub 直连失败时依次尝试公共加速镜像。 */
    suspend fun download(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        lastError = null
        val dir = File(context.cacheDir, "updates").apply {
            if (!exists()) mkdirs()
        }
        val candidates = buildList {
            add(url)
            if (url.startsWith("https://github.com/")) {
                MIRRORS.forEach { add(it + url) }
            }
        }
        var message: String? = null
        for (candidate in candidates) {
            val file = try {
                downloadOnce(candidate, dir)
            } catch (t: Throwable) {
                message = t.message ?: t.javaClass.simpleName
                null
            }
            if (file != null && verifyApk(context, file)) {
                return@withContext file
            }
            if (file != null) {
                message = "下载的安装包校验失败"
                file.delete()
            }
        }
        lastError = message ?: lastError ?: "网络异常"
        null
    }

    private fun downloadOnce(url: String, dir: File): File? {
        val target = File(dir, "update.apk")
        if (target.exists()) target.delete()
        var current = URL(url)
        var redirects = 0
        while (true) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location == null || ++redirects > 5) {
                    lastError = "下载重定向失败"
                    return null
                }
                current = URL(location)
                continue
            }
            if (code != 200) {
                connection.disconnect()
                lastError = "下载失败（HTTP $code）"
                return null
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            break
        }
        if (target.length() <= 0L) {
            lastError = "下载内容为空"
            return null
        }
        return target
    }

    /** 校验下载到的 APK 确实属于本应用且版本号更高，避免镜像返回错误页面。 */
    private fun verifyApk(context: Context, file: File): Boolean = try {
        if (file.length() <= 0L) {
            false
        } else {
            val magic = ByteArray(2)
            val isZip = file.inputStream().use { input ->
                input.read(magic) == 2 &&
                    magic[0] == 'P'.code.toByte() &&
                    magic[1] == 'K'.code.toByte()
            }
            if (!isZip) {
                false
            } else {
                val archive = context.packageManager.getPackageArchiveInfo(
                    file.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                if (archive == null || archive.packageName != context.packageName) {
                    false
                } else {
                    val newCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        archive.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        archive.versionCode.toLong()
                    }
                    val installed = context.packageManager.getPackageInfo(
                        context.packageName,
                        0
                    )
                    val installedCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        installed.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        installed.versionCode.toLong()
                    }
                    newCode > installedCode
                }
            }
        }
    } catch (t: Throwable) {
        false
    }

    /**
     * 安装已下载的 APK。没有「安装未知应用」权限时先打开系统授权页，
     * 授权后再次调用即可拉起系统安装器。
     */
    fun install(context: Context, apk: File): InstallResult {
        lastError = null
        if (!context.packageManager.canRequestPackageInstalls()) {
            if (!openUnknownSourcesSettings(context)) return InstallResult.FAILED
            return InstallResult.NEED_PERMISSION
        }
        return if (launchInstaller(context, apk)) {
            InstallResult.OPENED
        } else {
            InstallResult.FAILED
        }
    }

    private fun openUnknownSourcesSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (t: Throwable) {
        lastError = "无法打开「安装未知应用」设置"
        false
    }

    private fun launchInstaller(context: Context, apk: File): Boolean = try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (t: Throwable) {
        lastError = t.message ?: "无法启动系统安装器"
        false
    }

    private fun compareVersion(a: String, b: String): Int {
        val pa = a.split('.').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.').mapNotNull { it.toIntOrNull() }
        val size = maxOf(pa.size, pb.size)
        for (i in 0 until size) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
