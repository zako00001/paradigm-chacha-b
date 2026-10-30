package site.local.paradigmchacha

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScreenState(val snapshot: Snapshot? = null, val hasAccount: Boolean = false,
    val busy: Boolean = true, val progress: String = "正在准备", val notice: String? = null,
    val report: Rendered? = null, val showReport: Boolean = false, val saveStatus: String = "")

class MainModel(application: Application) : AndroidViewModel(application) {
    private val repository = Repository(application)
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    init {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { repository.cached() to repository.hasAccount() }
                mutable.value = ScreenState(snapshot = loaded.first, hasAccount = loaded.second, busy = false)
            } catch (_: Exception) { mutable.value = ScreenState(busy = false, notice = "本地账号无法读取，请重新授权。") }
        }
    }
    private fun progress(value: String) { mutable.update { it.copy(progress = value) } }
    fun notice(value: String) { mutable.update { it.copy(notice = value) } }
    fun clearNotice() { mutable.update { it.copy(notice = null) } }
    fun closeReport() { mutable.update { it.copy(showReport = false) } }
    private fun fetch(block: () -> Snapshot) {
        if (mutable.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, progress = "正在连接国服", notice = null) }
            try {
                val result = withContext(Dispatchers.IO) { block() }
                mutable.value = ScreenState(result, hasAccount = true, busy = false, notice = "已更新 ${result.scores.size} 条真实成绩")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val saved = withContext(Dispatchers.IO) { repository.cached() to repository.hasAccount() }
                mutable.value = ScreenState(saved.first, saved.second, busy = false,
                    notice = if(failure is AppError) failure.message else "连接未成功，请检查网络后重试。")
            }
        }
    }
    fun authorize(credential: JSONObject) = fetch { repository.authorize(credential, ::progress) }
    fun update() = fetch { repository.update(::progress) }
    fun generate() {
        val snapshot = mutable.value.snapshot ?: return
        if (mutable.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, progress = "正在生成 B50 图片") }
            try {
                val result = withContext(Dispatchers.IO) { repository.render(snapshot) }
                mutable.update { it.copy(busy = false, report = result, showReport = true, saveStatus = "",
                    notice = if (result.missingCovers > 0) "已生成，${result.missingCovers} 张曲绘暂不可用" else null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(busy = false, notice = "图片生成失败，请稍后重试。") } }
        }
    }
    fun savePicture() {
        val report = mutable.value.report ?: return
        if (mutable.value.busy || mutable.value.saveStatus in listOf("saving", "saved")) return
        mutable.update { it.copy(saveStatus = "saving") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.saveToGallery(report.file) }
                mutable.update { it.copy(saveStatus = "saved") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(saveStatus = "error") } }
        }
    }
    fun refreshArtwork() {
        if (mutable.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, progress = "正在更新曲库与曲绘") }
            try {
                val snapshot = withContext(Dispatchers.IO) { repository.refreshArtwork() }
                mutable.update { it.copy(snapshot = snapshot, busy = false, report = null,
                    showReport = false, notice = "曲库已更新，曲绘缓存已刷新；列表和生成图片时按需下载。") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(busy = false, notice = "曲库更新失败，请检查网络后重试。") } }
        }
    }
    fun clearAccount() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.clearAccount() }
            mutable.value = ScreenState(busy = false, notice = "已清除本机账号和成绩")
        }
    }
}

class MainActivity : ComponentActivity() {
    private val model: MainModel by viewModels()
    private val auth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uid = data?.getStringExtra("user_info_sdk_id")
        val token = data?.getStringExtra("user_info_sdk_token")
        val type = data?.getStringExtra("user_info_sdk_type")
        if (uid.isNullOrBlank() || token.isNullOrBlank() || type.isNullOrBlank()) {
            model.notice("未取得完整授权，请在好游快爆中确认登录后重试。")
        } else {
            model.authorize(JSONObject().put("userId", uid).put("token", token).put("loginType", type).put("gid", 24931)
                .put("avatar", data?.getStringExtra("user_info_sdk_avatar").orEmpty()))
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ChachaApp(model, ::authorize, ::share) }
    }
    private fun digest(algorithm: String, bytes: ByteArray) = MessageDigest.getInstance(algorithm).digest(bytes)
        .joinToString("") { Integer.toHexString((it.toInt() and 255) + 256).substring(1) }
    private fun authorize() {
        try {
            packageManager.getPackageInfo("com.xmcy.hykb", 0)
            val signer = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo!!.apkContentsSigners[0]
            val prefix = String(Base64.decode("U0ZsTFFpdE1UMGRKVGcK", Base64.DEFAULT), Charsets.UTF_8)
            val sign = digest("MD5", (prefix + digest("SHA1", signer.toByteArray())).toByteArray(Charsets.UTF_8))
            val intent = Intent().apply {
                component = ComponentName("com.xmcy.hykb", "com.xmcy.hykb.app.ui.mine.AuthorizeUserActivity")
                putExtra("app_id", "24931"); putExtra("app_name", "范式查查b"); putExtra("app_icon", "")
                putExtra("sign_info", sign); putExtra("user_info_sdk_quest", "login_from_purchasesdk")
            }
            auth.launch(intent)
        } catch (_: PackageManager.NameNotFoundException) { model.notice("请先安装好游快爆并登录你的国服账号。") }
        catch (_: Exception) { model.notice("无法打开快爆授权，请确认客户端已更新。") }
    }
    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.images", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newUri(contentResolver, "B50", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "分享 B50 图片"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChachaApp(model: MainModel, authorize: () -> Unit, share: (File) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = if(dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val updates = remember { Updates(context) }
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var author by remember { mutableStateOf(false) }
    fun openLink(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
    fun checkUpdate(manual: Boolean) {
        if (updateBusy) return
        updateBusy = true
        scope.launch {
            try {
                update = withContext(Dispatchers.IO) { updates.check(manual) }
                if (manual && update == null) model.notice("当前没有可用的新版本")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (manual) model.notice("检查更新失败，请检查网络后重试。") }
            finally { updateBusy = false }
        }
    }
    LaunchedEffect(Unit) { checkUpdate(false) }
    MaterialTheme(colorScheme = colors, shapes = Shapes(medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp))) {
        LaunchedEffect(state.notice) {
            state.notice?.let { snackbar.showSnackbar(it); model.clearNotice() }
        }
        Scaffold(
            topBar = { LargeTopAppBar(title = { Text("范式查查b", fontWeight = FontWeight.Bold) }, actions = {
                if(state.hasAccount) IconButton(onClick = model::update, enabled = !state.busy) {
                    Icon(Icons.Default.Refresh, contentDescription = "更新成绩")
                }
            }) },
            bottomBar = { NavigationBar {
                val labels = listOf("B50", "成绩", "我的")
                val icons = listOf(Icons.Default.Star, Icons.Default.List, Icons.Default.Person)
                labels.forEachIndexed { i,label -> NavigationBarItem(selected = tab == i, onClick = { tab=i },
                    icon = { Icon(icons[i], contentDescription = label) }, label = { Text(label) }) }
            } },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                val modifier = Modifier.widthIn(max = 840.dp).fillMaxSize()
                when(tab) {
                    0 -> B50Screen(state, modifier, authorize, model::generate, model::update)
                    1 -> ScoresScreen(state, modifier, authorize)
                    else -> AccountScreen(state, modifier, authorize, model::clearAccount,
                        { openLink(REPOSITORY_URL) }, { checkUpdate(true) }, updateBusy,
                        model::refreshArtwork, { author = true })
                }
            }
        }
        if (author) AlertDialog(onDismissRequest = { author = false }, title = { Text("关于作者") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("zako00001")
                Text("邮箱：1708108745@qq.com")
                Text("QQ：1708108745")
                TextButton(onClick = { openLink("https://github.com/zako00001") }) { Text("GitHub 主页") }
            } }, confirmButton = { TextButton(onClick = { author = false }) { Text("关闭") } })
        update?.let { available ->
            AlertDialog(onDismissRequest = { if (!updateBusy) update = null },
                title = { Text("发现新版本 ${available.version}") },
                text = { Text(if (updateBusy) "正在下载，请稍候…" else "下载后将打开系统安装界面，首次安装需允许本应用安装更新。") },
                confirmButton = { TextButton(enabled = !updateBusy, onClick = {
                    updateBusy = true
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) { updates.download(available) }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.images", file)
                            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                            update = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { model.notice("下载或安装未成功，请确认网络、安装权限及发布包版本。") }
                        finally { updateBusy = false }
                    }
                }) { Text("下载并安装") } },
                dismissButton = { Row {
                    TextButton(enabled = !updateBusy, onClick = { updates.ignore(available); update = null }) { Text("此版本不再提醒") }
                    TextButton(enabled = !updateBusy, onClick = { update = null }) { Text("取消") }
                } })
        }
        if(state.showReport && state.report != null) {
            ReportDialog(state, model::closeReport, model::savePicture, { share(state.report!!.file) })
        }
    }
}

@Composable private fun Welcome(authorize: () -> Unit, busy: Boolean) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp)) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Image(painterResource(R.drawable.icon_original), null, Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)))
            Text("连接快爆账号，查看真实成绩与 B35 + B15，生成自己的 B50 图片。", style=MaterialTheme.typography.bodyLarge)
            Button(onClick=authorize,enabled=!busy,modifier=Modifier.fillMaxWidth().height(56.dp)) { Text("通过好游快爆授权") }
        }
    }
}

@Composable private fun Busy(state: ScreenState) {
    if(state.busy) Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.progress,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun PlayerCard(snapshot: Snapshot) {
    Card(Modifier.fillMaxWidth(), colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                if(snapshot.player.avatar!=null) AsyncImage(snapshot.player.avatar, "账号头像",
                    Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)),contentScale=ContentScale.Crop)
                else Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),contentAlignment=Alignment.Center) {
                    Text(snapshot.player.name.take(1),style=MaterialTheme.typography.headlineMedium)
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(snapshot.player.name,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                    Text("#${snapshot.player.tag} · 国服",style=MaterialTheme.typography.bodyMedium)
                }
            }
            Text("%.4f".format(Locale.US,snapshot.totalRating),fontSize=42.sp,fontWeight=FontWeight.Bold,fontFamily=FontFamily.Monospace)
            Text("B35 ${snapshot.b35.size}/35  ·  B15 ${snapshot.b15.size}/15  ·  本地计算",style=MaterialTheme.typography.labelLarge)
            Text("游戏 Rating  %.4f".format(Locale.US,snapshot.player.officialRating),style=MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable private fun B50Screen(state: ScreenState, modifier: Modifier, authorize: () -> Unit, generate: () -> Unit, update: () -> Unit) {
    var pool by rememberSaveable { mutableIntStateOf(0) }
    val snapshot = state.snapshot
    LazyColumn(modifier,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Busy(state) }
        if(snapshot==null) {
            item { Welcome(authorize,state.busy) }
            if(state.hasAccount) item { OutlinedButton(onClick=update,enabled=!state.busy) { Text("重新更新成绩") } }
        } else {
            item { PlayerCard(snapshot) }
            item { Button(onClick=generate,enabled=!state.busy,modifier=Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Default.Star,null,Modifier.size(20.dp));Spacer(Modifier.width(10.dp));Text("生成 B50 图片")
            } }
            item { Text("${snapshot.scores.size} 条真实成绩 · ${time(snapshot.syncedAt)} 更新",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            if(snapshot.missingCharts>0) item { Text("${snapshot.missingCharts} 张谱面暂未收录，未计入 B50。",color=MaterialTheme.colorScheme.error) }
            item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                FilterChip(selected=pool==0,onClick={pool=0},label={Text("B15 · 新曲 ${snapshot.b15.size}")})
                FilterChip(selected=pool==1,onClick={pool=1},label={Text("B35 · 旧曲 ${snapshot.b35.size}")})
            } }
            val selected = if(pool==0)snapshot.b15 else snapshot.b35
            items(selected,key={it.address}) { score -> ScoreRow(score,selected.indexOf(score)+1) }
            item { Text("依据社区定数与分组规则计算，图片标记为候选 B50。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun ScoreRow(score: Score, rank: Int? = null) {
    Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            if(score.chart!=null) AsyncImage(score.chart.imageModel,null,Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)),contentScale=ContentScale.Crop)
            else Box(Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),contentAlignment=Alignment.Center) { Icon(Icons.Default.Info,null) }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text((rank?.let { "#$it  " } ?: "")+score.title,maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold)
                Text("${score.chart?.shortDifficulty ?: score.address.substringAfterLast('/').uppercase()} ${score.chart?.displayLevel.orEmpty()}  ·  ${score.status}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("%07d".format(score.score),style=MaterialTheme.typography.titleLarge,fontFamily=FontFamily.Monospace)
            }
            Column(horizontalAlignment=Alignment.End) {
                if(score.chart!=null) {
                    Text("%.2f".format(Locale.US,score.rating/100.0),fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                    Text("定数 %.1f".format(Locale.US,score.chart.constant),style=MaterialTheme.typography.labelSmall)
                } else Text("未计入 B50",style=MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable private fun ScoresScreen(state: ScreenState, modifier: Modifier, authorize: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val snapshot=state.snapshot
    val scores=remember(snapshot,query) { snapshot?.scores?.filter { query.isBlank() || it.title.contains(query,true) || it.address.contains(query,true) }
        ?.sortedWith(compareByDescending<Score>{it.rating}.thenByDescending{it.score}).orEmpty() }
    LazyColumn(modifier,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Busy(state) }
        if(snapshot==null) item { Welcome(authorize,state.busy) }
        else {
            item { Text("全部成绩",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold) }
            item { OutlinedTextField(value=query,onValueChange={query=it},label={Text("搜索曲名")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)) }
            item { Text("共 ${scores.size} 张谱面",style=MaterialTheme.typography.labelLarge) }
            items(scores,key={it.address}) { ScoreRow(it) }
        }
    }
}

@Composable private fun AccountScreen(state: ScreenState, modifier: Modifier, authorize: () -> Unit, clear: () -> Unit,
    repository: () -> Unit, checkUpdate: () -> Unit, updateBusy: Boolean,
    refreshArtwork: () -> Unit, aboutAuthor: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    LazyColumn(modifier,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { Busy(state) }
        if(state.snapshot!=null) item { PlayerCard(state.snapshot) }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("账号",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text("授权与成绩只保存在本机。通过好游快爆授权后，可随时更新国服成绩。")
            FilledTonalButton(onClick=authorize,enabled=!state.busy,modifier=Modifier.fillMaxWidth().height(52.dp)) { Text(if(state.hasAccount)"重新授权快爆账号" else "通过好游快爆授权") }
            if(state.hasAccount) TextButton(onClick={confirm=true},enabled=!state.busy) { Text("退出并清除本地账号",color=MaterialTheme.colorScheme.error) }
        } } }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("关于范式查查b",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text("版本 ${LocalContext.current.packageManager.getPackageInfo(LocalContext.current.packageName, 0).versionName}")
            TextButton(onClick=repository) { Text("GitHub 仓库") }
            TextButton(onClick=checkUpdate, enabled=!updateBusy) { Text(if(updateBusy) "正在检查或下载…" else "检查更新") }
            TextButton(onClick=refreshArtwork, enabled=!state.busy) { Text("更新曲库与曲绘") }
            Text("B50 = B35 + B15。CHAOTIC 不计入，使用社区定数与分季标记计算。",style=MaterialTheme.typography.bodyMedium)
            Text("定数、曲绘镜像及 Rating 公式参考 PRP；曲绘版权归原作者及游戏方。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("非官方工具，与游戏开发商及好游快爆无隶属关系。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        } } }
        item { TextButton(onClick=aboutAuthor) { Text("关于作者") } }
    }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("清除本地账号？")},
        text={Text("会移除本机保存的授权和成绩，已保存到相册的图片保留。")},
        confirmButton={TextButton(onClick={confirm=false;clear()}){Text("清除")}},dismissButton={TextButton(onClick={confirm=false}){Text("取消")}})
}

@Composable private fun ReportDialog(state: ScreenState, close: () -> Unit, save: () -> Unit, share: () -> Unit) {
    val file=state.report!!.file
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.95f).padding(horizontal=12.dp),shape=RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("B50 图片",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                    TextButton(onClick=close){Text("完成")}
                }
                AsyncImage(ImageRequest.Builder(LocalContext.current).data(file).memoryCacheKey("${file.path}:${file.lastModified()}").build(),
                    "生成的 B50 图片",Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)),contentScale=ContentScale.Fit)
                val feedback = when(state.saveStatus) {
                    "saving" -> "正在保存到相册…"
                    "saved" -> "已保存到相册 · 范式查查b"
                    "error" -> "保存失败，请检查存储空间后重试"
                    else -> state.notice.orEmpty()
                }
                Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment=Alignment.CenterStart) {
                    Crossfade(targetState=feedback, animationSpec=tween(180), label="saveFeedback") { message ->
                        Text(message,style=MaterialTheme.typography.bodySmall,
                            color=if(state.saveStatus=="error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Button(onClick=save,enabled=!state.busy && state.saveStatus !in listOf("saving","saved"),modifier=Modifier.weight(1f).height(52.dp)) {
                        Box(Modifier.size(18.dp),contentAlignment=Alignment.Center) {
                            if(state.saveStatus=="saving") CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                            else Icon(Icons.Default.Check,null,Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(when(state.saveStatus) { "saving" -> "保存中"; "saved" -> "已保存"; "error" -> "重试保存"; else -> "保存相册" })
                    }
                    FilledTonalButton(onClick=share,enabled=!state.busy && state.saveStatus!="saving",modifier=Modifier.weight(1f).height(52.dp)) { Icon(Icons.Default.Share,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("分享") }
                }
            }
        }
    }
}

private fun time(timestamp: Long) = SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(Date(timestamp))
