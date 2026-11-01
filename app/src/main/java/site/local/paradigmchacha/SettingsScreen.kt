package site.local.paradigmchacha

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun SettingsScreen(state: ScreenState, modifier: Modifier, authorize: () -> Unit, clear: () -> Unit,
    checkUpdate: () -> Unit, updateCovers: () -> Unit, setTheme: (String) -> Unit) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val context=LocalContext.current
    val available=!state.busy && !state.updatingCovers
    val themeLabel=when(state.themeMode) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" }
    fun browse(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).apply {
            selector=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
        }) } catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context,"未找到浏览器",android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    BackHandler(enabled=page!=null) { page=null }
    AnimatedContent(targetState=page,modifier=modifier,transitionSpec={
        val direction=if(targetState==null)-1 else 1
        (fadeIn(tween(200,40))+slideInHorizontally(spring(dampingRatio=.9f,stiffness=360f)){it/8*direction})
            .togetherWith(fadeOut(tween(110))+slideOutHorizontally(tween(180)){-it/12*direction})
    },label="settingsDetail") { detail ->
        if(detail==null) {
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                item {
                    val player=state.currentSnapshot?.player
                    Card(onClick={page="账号管理"},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)) {
                        Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer,RoundedCornerShape(16.dp)),contentAlignment=Alignment.Center) {
                                Text(player?.name?.take(1) ?: "我",style=MaterialTheme.typography.titleLarge)
                            }
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text(player?.name ?: "尚未连接账号",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                                Text(if(state.manualMode) "手动录分 · 数据保存在本机" else if(state.hasAccount) "国服账号已授权" else "账号与授权设置",style=MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.KeyboardArrowRight,"账号管理")
                        }
                    }
                }
                item { SettingsGroup {
                    SettingsEntry(Icons.Default.Person,"账号管理","快爆授权与本地账号",{page="账号管理"})
                    SettingsDivider()
                    SettingsEntry(Icons.Default.Settings,"外观与夜间模式",themeLabel,{page="外观与夜间模式"})
                } }
                item { SettingsGroup {
                    SettingsEntry(Icons.Default.Refresh,"软件更新",if(state.checkingUpdate) "正在检查更新…" else "当前版本 ${BuildConfig.VERSION_NAME}",{page="软件更新"})
                    SettingsDivider()
                    SettingsEntry(Icons.Default.Star,"曲库与曲绘",if(state.updatingCovers) state.coverProgress else "社区谱面、定数与曲绘",{page="曲库与曲绘"})
                    SettingsDivider()
                    SettingsEntry(Icons.Default.Share,"GitHub 仓库","查看源码与发行版",{browse(PROJECT_URL)})
                } }
                item { Text("关于",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(start=8.dp)) }
                item { SettingsGroup {
                    SettingsEntry(Icons.Default.Info,"关于范式查查b","版本、计算规则与致谢",{page="关于范式查查b"})
                    SettingsDivider()
                    SettingsEntry(Icons.Default.Person,"关于作者","zako00001",{page="关于作者"})
                } }
            }
        } else Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={page=null}) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回我的")}
                Text(detail,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            }
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                item { Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(24.dp).animateContentSize(spring(dampingRatio=.9f,stiffness=400f)),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        when(detail) {
                            "账号管理" -> {
                                Text(if(state.hasAccount) "国服账号已授权" else "连接国服账号",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                                Text("通过好游快爆授权读取自己的国服成绩，授权与成绩保存在本机。手动录分无需授权，记录单独保存。")
                                Button(onClick=authorize,enabled=available,modifier=Modifier.fillMaxWidth()) {Text(if(state.hasAccount) "重新授权快爆账号" else "通过好游快爆授权")}
                                if(state.hasAccount) TextButton(onClick={confirmClear=true},enabled=available) {Text("退出并清除本地国服账号",color=MaterialTheme.colorScheme.error)}
                            }
                            "外观与夜间模式" -> {
                                Text("主题",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                                listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach { (key,label) ->
                                    Row(Modifier.fillMaxWidth().clickable {setTheme(key)}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                                        RadioButton(selected=state.themeMode==key,onClick={setTheme(key)})
                                        Text(label,modifier=Modifier.padding(start=8.dp))
                                    }
                                }
                                Text("默认跟随系统：系统开启深色模式时，页面、弹窗和状态栏同步切换。配色使用系统动态色。",style=MaterialTheme.typography.bodySmall)
                            }
                            "软件更新" -> {
                                Text("当前版本 ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                                Text("进入软件时自动检查 GitHub 正式发行版。发现更新可取消，也可不再提醒该版本。")
                                if(state.checkingUpdate) LinearProgressIndicator(Modifier.fillMaxWidth())
                                Button(onClick=checkUpdate,enabled=!state.checkingUpdate && state.downloadPercent==null,modifier=Modifier.fillMaxWidth()) {
                                    Text(if(state.checkingUpdate) "正在检查更新…" else "检查软件更新")
                                }
                                TextButton(onClick={browse("$PROJECT_URL/releases")}) {Text("在浏览器查看发行版")}
                            }
                            "曲库与曲绘" -> {
                                Text("更新社区曲库",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                                Text("同步 PRP 社区谱面、定数和 B15 标记，刷新已游玩及手动录入谱面的曲绘，其他曲绘按需加载。新曲收录以社区镜像为准。")
                                if(state.updatingCovers) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(state.coverProgress)}
                                Button(onClick=updateCovers,enabled=available,modifier=Modifier.fillMaxWidth()) {Text(if(state.updatingCovers) "正在更新…" else "更新曲库与曲绘")}
                            }
                            "关于作者" -> {
                                Text("zako00001",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                                Text("邮箱：1708108745@qq.com")
                                Text("QQ：1708108745")
                                Text(AUTHOR_URL,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary,modifier=Modifier.clickable {browse(AUTHOR_URL)})
                                Button(onClick={browse(AUTHOR_URL)},modifier=Modifier.fillMaxWidth()) {Text("GitHub 主页")}
                            }
                            else -> {
                                Text("范式查查b ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                                Text("国服查分 / 手动录分\nB50 = B35 + B15，CHAOTIC 不计入。使用社区定数与分季信息在本机计算。")
                                Text("定数、曲绘镜像及 Rating 公式参考 PRP；曲绘版权归原作者及游戏方。")
                                Text("非官方工具，与游戏开发商及好游快爆无隶属关系。",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } }
            }
        }
    }
    if(confirmClear) AlertDialog(onDismissRequest={confirmClear=false},title={Text("清除本地国服账号？")},
        text={Text("会移除本机保存的国服授权和同步成绩。手动录分记录、已保存到相册的图片保留。")},
        confirmButton={TextButton(onClick={confirmClear=false;clear()}) {Text("清除")}},
        dismissButton={TextButton(onClick={confirmClear=false}) {Text("取消")}})
}

@Composable private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
        Column(content=content)
    }
}

@Composable private fun SettingsDivider() {HorizontalDivider(Modifier.padding(start=60.dp,end=16.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f))}

@Composable private fun SettingsEntry(icon: ImageVector,title: String,subtitle: String,click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=click).padding(horizontal=18.dp,vertical=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Icon(icon,null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Medium)
            Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.KeyboardArrowRight,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
