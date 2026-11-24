package com.mignnlapin.jilu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查 GitHub Release 更新。优先使用官方 API，被限流时改用发布页 / Atom。
 */
object UpdateManager {
    private const val REPO = "MignnLapin/JiLu"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val WEB_LATEST = "https://github.com/$REPO/releases/latest"
    private const val WEB_RELEASES = "https://github.com/$REPO/releases"
    private const val ATOM = "https://github.com/$REPO/releases.atom"
    private const val USER_AGENT = "JiLu"

    @Volatile
    var lastError: String? = null
        private set

    data class Info(val version: String, val url: String, val notes: String)

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
