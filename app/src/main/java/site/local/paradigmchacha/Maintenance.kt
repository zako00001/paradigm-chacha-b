package site.local.paradigmchacha

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

const val PROJECT_URL = "https://github.com/zako00001/paradigm-chacha-b"
const val AUTHOR_URL = "https://github.com/zako00001"
private const val RELEASE_API = "https://api.github.com/repos/zako00001/paradigm-chacha-b/releases/latest"
private const val CATALOG_URL = "https://api.prp.icel.site/api/v2/songs"

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

internal fun coverFile(context: Context, url: String) = File(context.filesDir, "covers/${sha256(url.toByteArray())}.jpg")

private fun connection(url: String): HttpURLConnection {
    val endpoint = URL(url)
    if (endpoint.protocol != "https") throw AppError("下载地址必须使用 HTTPS。")
    return (endpoint.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
        connectTimeout = 20000
        readTimeout = 30000
        setRequestProperty("User-Agent", "ParadigmChachaB/${BuildConfig.VERSION_NAME}")
    }
}

private fun fetch(url: String): ByteArray {
    val c = connection(url)
    try {
        if (c.responseCode != 200) {
            if (c.responseCode == 403 || c.responseCode == 429) throw AppError("请求过于频繁，请稍后再试。")
            throw IOException("HTTP ${c.responseCode}")
        }
        return c.inputStream.use { it.readBytes() }
    } finally { c.disconnect() }
}

private fun writeAtomic(file: File, bytes: ByteArray) {
    file.parentFile!!.mkdirs()
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try { stream.write(bytes); atomic.finishWrite(stream) }
    catch (e: Exception) { atomic.failWrite(stream); throw e }
}

data class AppRelease(val tag: String, val name: String, val notes: String, val url: String,
    val bytes: Long, val digest: String?)

class AppUpdates(private val context: Context) {
    private val preferences = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    fun ignore(tag: String) { preferences.edit().putString("ignored-tag", tag).apply() }
    fun ignored(tag: String) = preferences.getString("ignored-tag", null) == tag

    companion object {
        internal fun newer(remote: String, local: String): Boolean {
            fun numbers(value: String): List<Int> {
                val match = Regex("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(value)
                    ?: throw AppError("发布版本号格式不正确，请在仓库查看更新。")
                return match.groupValues.drop(1).map { it.toInt() }
            }
            val a = numbers(remote); val b = numbers(local)
            for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
            return false
        }

        internal fun release(json: JSONObject, current: String): AppRelease? {
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.getString("tag_name")
            if (!newer(tag, current)) return null
            val assets = json.getJSONArray("assets")
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.getString("name").endsWith(".apk", true) }
            val asset = apks.singleOrNull()
                ?: throw AppError("最新版本的 APK 附件不明确，请前往仓库 Releases 下载。")
            val url = asset.getString("browser_download_url")
            if (!url.startsWith("$PROJECT_URL/releases/download/")) throw AppError("更新包地址不属于软件仓库。")
            val digest = asset.optString("digest").takeIf { it.startsWith("sha256:") }?.substringAfter(':')
            return AppRelease(tag, json.optString("name").ifBlank { tag }, json.optString("body"), url,
                asset.getLong("size"), digest)
        }
    }

    fun check(): AppRelease? {
        val c = connection(RELEASE_API)
        try {
            c.setRequestProperty("Accept", "application/vnd.github+json")
            val status = c.responseCode
            if (status == 404) throw AppError("仓库暂未发布可下载的版本。")
            if (status == 403 || status == 429) throw AppError("GitHub 请求次数受限，请稍后再检查。")
            if (status != 200) throw AppError("无法检查更新（HTTP $status）。")
            val json = c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            return release(json, BuildConfig.VERSION_NAME)
        } finally { c.disconnect() }
    }

    @Suppress("DEPRECATION")
    fun validateApk(file: File) {
        val manager = context.packageManager
        val candidate = manager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw AppError("下载的文件不是有效 APK。")
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (candidate.packageName != context.packageName) throw AppError("更新包的包名不匹配，已停止安装。")
        if (candidate.longVersionCode <= installed.longVersionCode) throw AppError("更新包版本未递增，已停止安装。")
        val incoming = candidate.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet()
        val current = installed.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet()
        if (incoming.isNullOrEmpty() || incoming != current) throw AppError("更新包签名与当前软件不一致，无法覆盖安装。")
    }

    fun download(release: AppRelease, progress: (Int) -> Unit): File {
        val folder = File(context.filesDir, "updates").apply { mkdirs() }
        val partial = File(folder, "update.apk.part")
        val target = File(folder, "update.apk")
        val c = connection(release.url)
        try {
            if (c.responseCode != 200) throw AppError("更新包下载失败（HTTP ${c.responseCode}）。")
            val hash = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var lastPercent = -1
            c.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read); hash.update(buffer, 0, read); total += read
                    val percent = if (release.bytes > 0) (total * 100 / release.bytes).toInt().coerceIn(0, 100) else 0
                    if (percent != lastPercent) { progress(percent); lastPercent = percent }
                }
            } }
            if (total != release.bytes) throw AppError("更新包下载不完整，请重试。")
            val actual = hash.digest().joinToString("") { "%02x".format(it) }
            if (release.digest != null && !actual.equals(release.digest, true)) throw AppError("更新包校验失败，请重新下载。")
            validateApk(partial)
            java.nio.file.Files.move(partial.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally { c.disconnect(); partial.delete() }
    }
}

data class CatalogResult(val charts: Int, val covers: Int, val failed: Int)

/** 同步社区谱面与已游玩封面；没有映射的未来游戏 ID 仍显示为未收录。 */
class CatalogUpdates(private val context: Context) {
    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun refresh(existing: JSONObject, played: Set<String>, progress: (String) -> Unit): CatalogResult {
        progress("正在同步曲库")
        val rows = JSONArray(String(fetch(CATALOG_URL), Charsets.UTF_8))
        if (rows.length() == 0) throw AppError("曲库返回为空，未替换本地曲库。")
        val known = existing.getJSONObject("charts")
        val knownSongs = known.keys().asSequence().groupBy { it.substringBeforeLast('/') }
        val addresses = mutableMapOf<String, MutableSet<String>>()
        val byWiki = mutableMapOf<String, String>()
        knownSongs.forEach { (address, keys) ->
            val c = known.getJSONObject(keys.first())
            val pair = normalized(c.getString("title")) + "\n" + normalized(c.getString("artist"))
            addresses.getOrPut(pair) { mutableSetOf() }.add(address)
            val wiki = Uri.parse(c.getString("coverUrl")).lastPathSegment.orEmpty().removePrefix("Cover_").substringBeforeLast('.')
            if (wiki.isNotBlank()) byWiki[wiki] = address
        }
        val merged = JSONObject()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val wiki = row.getString("wiki_id")
            val difficulty = row.getString("difficulty")
            if (wiki.isBlank() || '/' in wiki || difficulty !in listOf("detected", "invaded", "massive", "reboot", "chaotic"))
                throw AppError("曲库谱面标识不正确，未替换本地曲库。")
            val pair = normalized(row.getString("title")) + "\n" + normalized(row.getString("artist"))
            val address = byWiki[wiki] ?: addresses[pair]?.singleOrNull() ?: "s/$wiki"
            val chartAddress = "$address/$difficulty"
            val previous = known.optJSONObject(chartAddress)
            val constant = row.getDouble("level")
            if (!constant.isFinite() || constant < 0 || constant > 30) throw AppError("曲库定数格式异常，未替换本地曲库。")
            val cover = row.getString("cover")
            if (cover.contains('/') || cover.contains('\\') || cover.isBlank()) throw AppError("曲绘地址格式异常。")
            val url = "https://prp.icel.site/cover/$cover"
            merged.put(chartAddress, JSONObject().put("title", row.getString("title")).put("artist", row.getString("artist"))
                .put("difficulty", difficulty).put("displayLevel", previous?.optString("displayLevel") ?: constant.toString())
                .put("constant", constant).put("b15", row.getBoolean("b15")).put("id", row.getInt("id"))
                .put("version", row.optString("version")).put("coverUrl", url)
                .put("coverAsset", if (previous?.optString("coverUrl") == url) previous.optString("coverAsset") else ""))
        }
        val catalog = JSONObject().put("source", CATALOG_URL).put("updatedAt", System.currentTimeMillis()).put("charts", merged)
        val urls = played.mapNotNull { merged.optJSONObject(it)?.getString("coverUrl") }.distinct()
        var saved = 0; var failed = 0
        urls.forEachIndexed { index, url ->
            progress("正在更新曲绘 ${index + 1}/${urls.size}")
            try {
                val bytes = fetch(url)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("invalid image")
                writeAtomic(coverFile(context, url), bytes); saved++
            } catch (_: IOException) { failed++ }
        }
        // 完成同步后原子替换；下载封面失败不破坏已有文件。
        writeAtomic(File(context.filesDir, "catalog.json"), catalog.toString().toByteArray(Charsets.UTF_8))
        return CatalogResult(merged.length(), saved, failed)
    }
}
