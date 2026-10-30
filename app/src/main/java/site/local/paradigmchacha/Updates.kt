package site.local.paradigmchacha

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

const val REPOSITORY_URL = "https://github.com/zako00001/paradigm-chacha-b"
data class AppUpdate(val version: String, val url: String)

class Updates(private val context: Context) {
    private val preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private fun connection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20000
        readTimeout = 25000
        setRequestProperty("User-Agent", "ParadigmChacha")
    }
    fun check(manual: Boolean): AppUpdate? {
        val connection = connection("https://api.github.com/repos/zako00001/paradigm-chacha-b/releases/latest")
        val release = try {
            if (connection.responseCode == 404) return null
            check(connection.responseCode == 200) { "更新服务暂时不可用" }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
        val tag = release.getString("tag_name")
        val version = tag.removePrefix("v")
        val parts = version.split('.').map { it.toIntOrNull() ?: return null }
        val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            .split('.').map { it.toIntOrNull() ?: 0 }
        val difference = (0 until maxOf(parts.size, current.size)).firstOrNull {
            parts.getOrElse(it) { 0 } != current.getOrElse(it) { 0 }
        } ?: return null
        if (parts.getOrElse(difference) { 0 } < current.getOrElse(difference) { 0 }) return null
        if (!manual && preferences.getString("ignored", "") == tag) return null
        val assets = release.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk", true)) {
                val url = asset.getString("browser_download_url")
                require(url.startsWith("$REPOSITORY_URL/releases/download/"))
                return AppUpdate(tag, url)
            }
        }
        error("新版本尚未提供 APK")
    }
    fun ignore(update: AppUpdate) { preferences.edit().putString("ignored", update.version).apply() }
    fun download(update: AppUpdate): File {
        val file = File(context.cacheDir, "updates/update.apk")
        file.parentFile!!.mkdirs()
        val connection = connection(update.url)
        try {
            check(connection.responseCode == 200) { "下载失败" }
            connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            val apk = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, 0)) { "安装包无效" }
            require(apk.packageName == context.packageName) { "安装包不属于本软件" }
            val installed = context.packageManager.getPackageInfo(context.packageName, 0)
            require(apk.longVersionCode > installed.longVersionCode) { "安装包版本号未递增" }
            return file
        } catch (failure: Exception) { file.delete(); throw failure }
        finally { connection.disconnect() }
    }
}
