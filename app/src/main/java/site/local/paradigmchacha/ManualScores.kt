package site.local.paradigmchacha

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.util.Locale

@Composable internal fun ManualScoresScreen(state: ScreenState, modifier: Modifier,
    savePlayer: (String, String) -> Unit, saveScore: (String, Int, Int) -> Unit, deleteScore: (String) -> Unit) {
    val snapshot = state.manualSnapshot
    var query by rememberSaveable { mutableStateOf("") }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Score?>(null) }
    var deleting by remember { mutableStateOf<Score?>(null) }
    var profile by remember { mutableStateOf(false) }
    val enabled = !state.busy && !state.updatingCovers && snapshot != null
    val scores = remember(snapshot, query) { snapshot?.scores.orEmpty()
        .filter { query.isBlank() || it.title.contains(query,true) || it.address.contains(query,true) }
        .sortedWith(compareByDescending<Score> { it.rating }.thenByDescending { it.score }) }
    LazyColumn(modifier,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Text("手动成绩",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold) }
        item { Text("无需游戏账号。每张谱面保存一条最佳成绩，保存后自动计算 B35 + B15。",style=MaterialTheme.typography.bodyMedium) }
        if(state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.progress) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Button(onClick={editing=null;showEditor=true},enabled=enabled,modifier=Modifier.weight(1f).height(52.dp)) { Text("添加成绩") }
            OutlinedButton(onClick={profile=true},enabled=enabled,modifier=Modifier.weight(1f).height(52.dp)) { Text("编辑玩家资料") }
        } }
        item { OutlinedTextField(value=query,onValueChange={query=it},label={Text("搜索已录入曲名")},singleLine=true,
            modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)) }
        item { Text("共 ${snapshot?.scores?.size ?: 0} 张谱面 · 点击成绩可修改",style=MaterialTheme.typography.labelLarge) }
        if(scores.isEmpty()) item { Text(if(query.isBlank()) "还没有手动成绩，点击“添加成绩”开始录入。" else "未找到匹配记录。") }
        items(scores,key={it.address}) { score ->
            Column(Modifier.animateItem()) {
                Box(Modifier.clickable(enabled=enabled) { editing=score;showEditor=true }) { ScoreRow(score) }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    TextButton(onClick={editing=score;showEditor=true},enabled=enabled) { Text("修改") }
                    TextButton(onClick={deleting=score},enabled=enabled) { Text("删除",color=MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if(showEditor) ManualScoreDialog(state.manualCharts,snapshot?.scores.orEmpty(),editing,
        {showEditor=false}, {address,score,flags -> saveScore(address,score,flags);showEditor=false})
    if(profile && snapshot!=null) ManualPlayerDialog(snapshot.player,{profile=false}) {name,tag -> savePlayer(name,tag);profile=false}
    deleting?.let { row -> AlertDialog(onDismissRequest={deleting=null},title={Text("删除这条手动成绩？")},
        text={Text("${row.title} · ${row.chart?.shortDifficulty.orEmpty()}\n仅删除手动录分记录。")},
        confirmButton={TextButton(onClick={deleteScore(row.address);deleting=null}) {Text("删除")}},
        dismissButton={TextButton(onClick={deleting=null}) {Text("取消")}}) }
}

@Composable private fun ManualPlayerDialog(player: Player, close: () -> Unit, save: (String,String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(player.name) }
    var tag by rememberSaveable { mutableStateOf(player.tag.takeUnless {it=="MANUAL"}.orEmpty()) }
    AlertDialog(onDismissRequest=close,title={Text("手动玩家资料")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("昵称和编号将填入生成的图片。")
        OutlinedTextField(value=name,onValueChange={if(it.length<=32)name=it},singleLine=true,label={Text("昵称")})
        OutlinedTextField(value=tag,onValueChange={if(it.length<=32)tag=it},singleLine=true,label={Text("玩家编号（可选）")})
    }},confirmButton={TextButton(onClick={save(name,tag)},enabled=name.isNotBlank()) {Text("保存")}},
        dismissButton={TextButton(onClick=close) {Text("取消")}})
}

@Composable private fun ManualScoreDialog(charts: List<Chart>, records: List<Score>, editing: Score?,
    close: () -> Unit, save: (String,Int,Int) -> Unit) {
    var selected by remember { mutableStateOf(editing?.chart) }
    var query by rememberSaveable { mutableStateOf("") }
    var difficulty by rememberSaveable { mutableStateOf("") }
    var scoreText by rememberSaveable { mutableStateOf(editing?.score?.toString().orEmpty()) }
    var flags by rememberSaveable { mutableIntStateOf(editing?.flags ?: 4) }
    val value = scoreText.toIntOrNull()
    val matches = remember(charts,query,difficulty) { charts.filter {
        (difficulty.isEmpty() || it.difficulty==difficulty) &&
            (query.isBlank() || it.title.contains(query.trim(),true) || it.artist.contains(query.trim(),true) || it.address.contains(query.trim(),true))
    } }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal=16.dp),shape=RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(editing==null) "添加手动成绩" else "修改手动成绩",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                AnimatedContent(targetState=selected,modifier=Modifier.weight(1f),
                    transitionSpec={
                        (fadeIn(tween(200,30))+slideInHorizontally(spring(dampingRatio=.9f,stiffness=380f)){it/12})
                            .togetherWith(fadeOut(tween(100))+slideOutHorizontally(tween(160)){-it/16})
                    },label="manualChartSelection") { chosen ->
                Column(if(chosen==null) Modifier.fillMaxSize() else Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(chosen==null) {
                    OutlinedTextField(value=query,onValueChange={query=it},label={Text("搜索曲名 / 曲师")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("" to "全部","detected" to "DET","invaded" to "INV","massive" to "MSV","reboot" to "RBT").forEach { (key,label) ->
                            FilterChip(selected=difficulty==key,onClick={difficulty=key},label={Text(label)})
                        }
                    }
                    Text("${matches.size} 张谱面 · 选择曲目与难度",style=MaterialTheme.typography.labelMedium)
                    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(matches.isEmpty()) item {Text("未找到谱面，可在“我的”更新曲库后再搜索。")}
                        items(matches,key={it.address}) { chart ->
                            OutlinedCard(onClick={
                                selected=chart
                                val previous=records.find {it.address==chart.address}
                                scoreText=previous?.score?.toString().orEmpty();flags=previous?.flags ?: 4
                            },modifier=Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    ChartCover(chart,Modifier.size(72.dp))
                                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Text(chart.title,fontWeight=FontWeight.SemiBold)
                                    Text("${chart.shortDifficulty} ${chart.displayLevel} · 定数 %.1f · ${if(chart.isNew) "B15 新曲" else "B35 旧曲"}".format(Locale.US,chart.constant),style=MaterialTheme.typography.bodySmall)
                                    if(records.any {it.address==chart.address}) Text("已录入 · 选择后可修改",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val chart=chosen
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                        ChartCover(chart,Modifier.size(88.dp))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(chart.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Text("${chart.shortDifficulty} ${chart.displayLevel} · 定数 %.1f · ${if(chart.isNew) "B15 新曲" else "B35 旧曲"}".format(Locale.US,chart.constant),style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    if(editing==null) TextButton(onClick={selected=null}) {Text("更换谱面")}
                    OutlinedTextField(value=scoreText,onValueChange={if(it.length<=7 && it.all(Char::isDigit))scoreText=it},label={Text("分数（0～1010000）")},
                        singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),
                        isError=scoreText.isNotEmpty() && (value==null || value !in 0..1010000))
                    if(value!=null && value in 0..1010000) Text("单曲 Rating：%.2f".format(Locale.US,Repository.singleRating(chart.constant,value)/100.0),color=MaterialTheme.colorScheme.primary)
                    Text("达成状态",style=MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "FAIL",4 to "CLR",5 to "FC",7 to "AD").forEach { (key,label) ->
                            FilterChip(selected=flags==key,onClick={flags=key},label={Text(label)})
                        }
                    }
                    if(records.any {it.address==chart.address}) Text("保存将替换这张谱面原有的手动成绩。",style=MaterialTheme.typography.bodySmall)
                }
                }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    TextButton(onClick=close) {Text("取消")}
                    Button(onClick={selected?.let {save(it.address,value!!,flags)}},enabled=selected!=null && value!=null && value in 0..1010000) {Text("保存成绩")}
                }
            }
        }
    }
}

/** 曲绘优先读取随包与已下载文件，未缓存的图片由 Coil 按需加载。 */
@Composable internal fun ChartCover(chart: Chart?, modifier: Modifier = Modifier) {
    val context=LocalContext.current
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),contentAlignment=Alignment.Center) {
        Icon(Icons.Default.Star,null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.5f))
        if(chart!=null) AsyncImage(
            ImageRequest.Builder(context).data(chart.imageModel)
                .memoryCacheKey(chart.downloadedCover?.let {"${it.path}:${it.lastModified()}"} ?: chart.imageModel.toString())
                .crossfade(180).build(),
            contentDescription="${chart.title} 曲绘",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
    }
}
