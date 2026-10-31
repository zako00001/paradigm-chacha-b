package site.local.paradigmchacha

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

data class Chart(
    val address: String, val title: String, val artist: String, val difficulty: String,
    val displayLevel: String, val constant: Double, val isNew: Boolean, val id: Int,
    val coverUrl: String, val coverAsset: String, val downloadedCover: File? = null,
) {
    val shortDifficulty get() = when (difficulty) {
        "detected" -> "DET"; "invaded" -> "INV"; "massive" -> "MSV"; "reboot" -> "RBT"
        else -> "CHAOTIC"
    }
    val imageModel get(): Any = downloadedCover ?: if (coverAsset.isNotEmpty()) "file:///android_asset/$coverAsset" else coverUrl
}

data class Score(val address: String, val score: Int, val flags: Int, val chart: Chart?, val rating: Int) {
    val title get() = chart?.title ?: address.substringBeforeLast('/').substringAfterLast('/')
    val status get() = when { flags and 2 != 0 -> "AD"; flags and 1 != 0 -> "FC"; flags and 4 != 0 -> "CLR"; else -> "FAIL" }
}

data class Player(val name: String, val tag: String, val officialRating: Double, val avatar: File?)
data class Snapshot(val player: Player, val scores: List<Score>, val syncedAt: Long) {
    private val eligible = scores.filter { it.chart != null && !it.address.endsWith("/chaotic") && it.score > 0 }
        .sortedWith(compareByDescending<Score> { it.rating }.thenByDescending { it.chart!!.id })
    val b15 = eligible.filter { it.chart!!.isNew }.take(15)
    val b35 = eligible.filter { !it.chart!!.isNew }.take(35)
    val totalRating = (b15 + b35).sumOf { it.rating } / 5000.0
    val missingCharts = scores.count { it.chart == null && !it.address.endsWith("/chaotic") && it.score > 0 }
}

class AppError(message: String) : Exception(message)

/** 账号凭据用 Android Keystore 加密；成绩和图片不包含凭据。 */
private class AccountStore(private val context: Context) {
    private val file = File(context.filesDir, "account.bin")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("china-account", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("china-account", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): JSONObject? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    fun save(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        file.writeBytes(cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8)))
    }
    fun clear() { file.delete() }
}

class Repository(private val context: Context) {
    private val accounts = AccountStore(context)
    private val snapshotFile = File(context.filesDir, "snapshot.json")
    private val avatarFile = File(context.filesDir, "avatar.jpg")
    private val key by lazy { context.assets.open("protocol.bin").use { it.readBytes() } }
    private var chartCache: Map<String, Chart>? = null
    private fun catalog(): JSONObject {
        val downloaded = File(context.filesDir, "catalog.json")
        return if (downloaded.exists()) JSONObject(downloaded.readText())
        else JSONObject(context.assets.open("charts.json").bufferedReader().use { it.readText() })
    }
    private val charts: Map<String, Chart> get() = chartCache ?: run {
        val raw = catalog().getJSONObject("charts")
        raw.keys().asSequence().associateWith { address ->
            val c = raw.getJSONObject(address)
            Chart(address, c.getString("title"), c.getString("artist"), c.getString("difficulty"),
                c.getString("displayLevel"), c.getDouble("constant"), c.getBoolean("b15"), c.getInt("id"),
                c.getString("coverUrl"), c.optString("coverAsset"), coverFile(context, c.getString("coverUrl")).takeIf { it.exists() })
        }.also { chartCache = it }
    }

    fun updateCovers(progress: (String) -> Unit): CatalogResult {
        val played = cached()?.scores?.map { it.address }?.toSet().orEmpty()
        val result = CatalogUpdates(context).refresh(catalog(), played, progress)
        chartCache = null
        return result
    }

    fun hasAccount() = accounts.read() != null
    fun cached(): Snapshot? = if (snapshotFile.exists()) parseSnapshot(JSONObject(snapshotFile.readText())) else null

    private fun parseSnapshot(raw: JSONObject): Snapshot {
        val info = raw.getJSONObject("profile")
        val rows = raw.getJSONArray("scores")
        val scores = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val address = row.getString("chart_id")
            val chart = charts[address]
            val score = row.getInt("score")
            Score(address, score, row.optInt("grade"), chart, if (chart == null) 0 else singleRating(chart.constant, score))
        }
        return Snapshot(Player(info.getString("username"), info.optString("username_id"),
            info.optDouble("rating", 0.0) / 10000.0, avatarFile.takeIf { it.exists() }), scores, raw.getLong("syncedAt"))
    }

    fun authorize(credential: JSONObject, progress: (String) -> Unit): Snapshot {
        val uid = credential.getString("userId")
        if (uid.toLongOrNull() == null || credential.optString("token").isBlank()) throw AppError("快爆未返回完整授权，请重试。")
        val previous = accounts.read()
        val device = if (previous?.optString("uid") == uid) previous.optString("deviceid") else UUID.randomUUID().toString()
        val account = JSONObject().put("uid", uid).put("deviceid", device).put("credential", credential)
        progress("正在确认快爆授权")
        exchangeSession(account)
        if (previous?.optString("uid") != uid) {
            snapshotFile.delete()
            avatarFile.delete()
        }
        accounts.save(account)
        return sync(account, progress)
    }

    fun update(progress: (String) -> Unit): Snapshot {
        val account = accounts.read() ?: throw AppError("请先通过好游快爆授权。")
        return sync(account, progress)
    }

    private fun exchangeSession(account: JSONObject) {
        val credential = account.getJSONObject("credential")
        val body = JSONObject().put("c", "authgame").put("a", "getAccessToken").put("v", "1546")
            .put("app_id", "24931").put("uid", account.getString("uid"))
            .put("type", credential.getString("loginType")).put("user_token", credential.getString("token"))
        val sdk = postJson("https://api.3839app.com/kuaibao/android/devsdk.php", body, mapOf("User-Agent" to "okhttp/3.12.0"))
        val access = sdk.optJSONObject("result")?.optString("access_token").orEmpty()
        if (access.isBlank()) throw AppError("快爆授权已过期，请重新授权。")
        val login = postJson("$GAME/account/login", JSONObject().put("uid", account.getString("uid").toLong()).put("access_token", access),
            mapOf("User-Agent" to "BestHTTP", "X-Device-Id" to account.getString("deviceid")))
        val session = login.optString("session")
        if (session.isBlank()) throw AppError("国服登录未成功，请稍后重试。")
        account.put("session", session)
    }

    private fun sync(account: JSONObject, progress: (String) -> Unit): Snapshot {
        progress("正在读取国服成绩")
        var best = gameGet("/server/play/get_best", account)
        if (best.first in listOf(400, 401, 403)) {
            exchangeSession(account)
            accounts.save(account)
            best = gameGet("/server/play/get_best", account)
        }
        if (best.first != 200 || !best.second.optString("status").equals("OK", true) || !best.second.has("data"))
            throw AppError("成绩读取未成功（HTTP ${best.first}），请稍后重试或重新授权。")
        progress("正在读取玩家资料")
        val profile = gameGet("/server/user/get_info", account)
        if (profile.first != 200 || !profile.second.has("username")) throw AppError("玩家资料读取失败，请稍后重试。")
        val avatar = account.getJSONObject("credential").optString("avatar")
        if (avatar.isNotBlank()) {
            try {
                val bytes = getBytes(avatar.replaceFirst("http://", "https://"))
                if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null) avatarFile.writeBytes(bytes)
                else avatarFile.delete()
            } catch (_: java.io.IOException) { avatarFile.delete() }
        }
        val raw = JSONObject().put("owner", account.getString("uid")).put("profile", profile.second)
            .put("scores", best.second.getJSONArray("data")).put("syncedAt", System.currentTimeMillis())
        snapshotFile.writeText(raw.toString())
        return parseSnapshot(raw)
    }

    private data class Response(val code: Int, val bytes: ByteArray, val session: String?)
    private fun http(url: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap()): Response {
        val endpoint = URL(url)
        if (endpoint.protocol != "https") throw AppError("仅支持安全的 HTTPS 连接。")
        val connection = endpoint.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = 20000
            connection.readTimeout = 25000
            connection.instanceFollowRedirects = true
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: byteArrayOf()
            return Response(code, bytes, connection.getHeaderField("X-New-session"))
        } finally { connection.disconnect() }
    }

    private fun postJson(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val response = http(url, body.toString().toByteArray(Charsets.UTF_8), headers + ("Content-Type" to "application/json;charset=utf-8"))
        if (response.code != 200) throw AppError("授权服务暂时不可用（HTTP ${response.code}）。")
        return JSONObject(String(response.bytes, Charsets.UTF_8))
    }

    private fun gameGet(path: String, account: JSONObject): Pair<Int, JSONObject> {
        val headers = mapOf("User-Agent" to "BestHTTP", "X-game-version" to "4.12", "X-api-version" to "98",
            "X-platform" to "Android", "X-Language" to "en", "X-UID" to account.getString("uid"),
            "X-Device-Id" to account.getString("deviceid"), "X-Session" to account.getString("session"))
        val response = http(GAME + path, headers = headers)
        response.session?.takeIf { it.isNotBlank() }?.let { account.put("session", it); accounts.save(account) }
        val text = String(response.bytes, Charsets.UTF_8).trimStart()
        val json = if (text.startsWith('{')) JSONObject(text) else {
            if (response.bytes.size < 32 || response.bytes.size % 16 != 0) throw AppError("服务器响应格式已变化，请更新应用。")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(response.bytes.copyOfRange(0, 16)))
            JSONObject(String(cipher.doFinal(response.bytes.copyOfRange(16, response.bytes.size)), Charsets.UTF_8))
        }
        return response.code to json
    }

    private fun getBytes(url: String): ByteArray {
        val response = http(url)
        if (response.code != 200) throw java.io.IOException("image unavailable")
        return response.bytes
    }

    fun cover(chart: Chart): Bitmap? {
        val downloaded = coverFile(context, chart.coverUrl)
        if (downloaded.exists()) return BitmapFactory.decodeFile(downloaded.path)
        if (chart.coverAsset.isNotBlank()) return context.assets.open(chart.coverAsset).use { BitmapFactory.decodeStream(it) }
        val hash = MessageDigest.getInstance("SHA-256").digest(chart.coverUrl.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(context.cacheDir, "covers/$hash.jpg")
        if (!file.exists()) {
            try { file.parentFile!!.mkdirs(); file.writeBytes(getBytes(chart.coverUrl)) }
            catch (_: java.io.IOException) { return null }
        }
        return BitmapFactory.decodeFile(file.path)
    }

    fun render(snapshot: Snapshot): Rendered = B50Renderer(context, this).render(snapshot)

    fun saveToGallery(file: File): Uri {
        val name = "范式查查b_B50_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/范式查查b")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw AppError("无法创建相册图片。")
        try {
            context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw AppError("无法写入相册图片。")
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (failure: Exception) { context.contentResolver.delete(uri, null, null); throw failure }
        return uri
    }

    fun clearAccount() {
        accounts.clear()
        snapshotFile.delete()
        avatarFile.delete()
        File(context.cacheDir, "reports/b50.png").delete()
    }

    companion object {
        private const val GAME = "https://api.paradigmchina.tunergames.com"
        // Formula attribution: assets/rating-LICENSE.txt (PRP Go, MIT).
        fun singleRating(level: Double, raw: Int): Int {
            val score = raw.coerceIn(0, 1010000)
            val r = when {
                score >= 1009000 -> level * 10 + 7 + 3 * ((score - 1009000) / 1000.0).pow(1.35)
                score >= 1000000 -> 10 * (level + 2 * (score - 1000000) / 30000.0)
                else -> {
                    val bounds = intArrayOf(900000, 930000, 950000, 970000, 980000, 990000)
                    val reward = intArrayOf(3, 1, 1, 1, 1, 1)
                    bounds.indices.filter { score >= bounds[it] }.sumOf { reward[it] } + 10 * (level * (score / 1000000.0).pow(1.5) - 0.9)
                }
            }
            return floor(max(0.0, r) * 100 + 0.00002).toInt()
        }
    }
}
