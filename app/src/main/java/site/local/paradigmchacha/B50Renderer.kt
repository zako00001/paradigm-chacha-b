package site.local.paradigmchacha

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Rendered(val file: File, val missingCovers: Int)

/** 现有 B50 模板的 Android Canvas 实现。所有文字来自已授权的真实数据。 */
class B50Renderer(private val context: Context, private val repository: Repository) {
    private lateinit var canvas: Canvas
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val white = "#f6f7ff"
    private val muted = "#b8bdd6"

    private fun rect(x: Float, y: Float, right: Float, bottom: Float, color: String) {
        paint.shader = null; paint.color = Color.parseColor(color); paint.style = Paint.Style.FILL
        canvas.drawRect(x, y, right, bottom, paint)
    }
    private fun polygon(color: String, vararg xy: Float) {
        val p = Path().apply { moveTo(xy[0], xy[1]); for (i in 2 until xy.size step 2) lineTo(xy[i], xy[i+1]); close() }
        paint.shader = null; paint.color = Color.parseColor(color); paint.style = Paint.Style.FILL
        canvas.drawPath(p, paint)
    }
    private fun line(x: Float, y: Float, right: Float, bottom: Float, color: String, width: Float = 2f) {
        paint.shader = null; paint.color = Color.parseColor(color); paint.strokeWidth = width
        canvas.drawLine(x, y, right, bottom, paint)
    }
    private fun text(value: String, x: Float, top: Float, size: Float, color: String = white,
                     align: Paint.Align = Paint.Align.LEFT, bold: Boolean = false, number: Boolean = false, maxWidth: Float = 0f) {
        paint.shader = null; paint.style = Paint.Style.FILL; paint.color = Color.parseColor(color)
        paint.textSize = size; paint.textAlign = align
        paint.typeface = Typeface.create(if (number) "monospace" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        var shown = value
        if (maxWidth > 0 && paint.measureText(shown) > maxWidth) {
            while (shown.isNotEmpty() && paint.measureText("$shown…") > maxWidth) shown = shown.dropLast(1)
            shown += "…"
        }
        canvas.drawText(shown, x, top - paint.fontMetrics.ascent, paint)
    }
    private fun image(bitmap: Bitmap, box: RectF) {
        val ratio = maxOf(box.width() / bitmap.width, box.height() / bitmap.height)
        val width = bitmap.width * ratio; val height = bitmap.height * ratio
        canvas.save(); canvas.clipRect(box)
        paint.shader = null; paint.alpha = 255
        canvas.drawBitmap(bitmap, null, RectF(box.centerX()-width/2, box.centerY()-height/2,
            box.centerX()+width/2, box.centerY()+height/2), paint)
        canvas.restore()
    }
    private fun difficultyColor(value: String) = when (value) {
        "detected" -> "#42b991"; "invaded" -> "#398fcf"; "massive" -> "#b83be5"; else -> "#b89545"
    }
    private fun section(y: Float, title: String, english: String, average: Double) {
        rect(72f, y, 1728f, y+70, "#24283d")
        polygon("#02a6cc", 72f,y,488f,y,533f,y+70,112f,y+70)
        polygon("#14d4ec", 72f,y,103f,y,144f,y+70,112f,y+70)
        text(title,170f,y+8,39f,bold=true)
        text(english,565f,y+24,25f,muted)
        text("AVG  %.4f".format(Locale.US,average),1696f,y+22,30f,align=Paint.Align.RIGHT,number=true)
    }

    private fun card(score: Score, rank: Int, x: Float, y: Float): Boolean {
        val c = score.chart!!
        rect(x,y,x+318,y+232,"#77778f")
        rect(x+2,y+2,x+316,y+230,"#0d0f1c")
        val cover = repository.cover(c)
        if (cover != null) {
            image(cover,RectF(x+2,y+2,x+316,y+148));cover.recycle()
        } else text("曲绘暂不可用",x+159,y+64,24f,muted,Paint.Align.CENTER)
        paint.shader = LinearGradient(0f,y,0f,y+148,
            intArrayOf(0x99050712.toInt(),0x10050712,0xe5050712.toInt()),floatArrayOf(0f,.48f,1f),Shader.TileMode.CLAMP)
        paint.color = Color.WHITE
        canvas.drawRect(x+2,y+2,x+316,y+148,paint);paint.shader = null
        text("#%02d".format(rank),x+13,y+8,29f,number=true,bold=true)
        polygon(difficultyColor(c.difficulty),x+213,y+3,x+316,y+3,x+316,y+38,x+237,y+38)
        text(c.shortDifficulty,x+306,y+6,23f,align=Paint.Align.RIGHT,bold=true)
        text(c.displayLevel,x+306,y+40,31f,align=Paint.Align.RIGHT,number=true)
        text(c.title,x+12,y+110,23f,maxWidth=294f)
        text("%07d".format(score.score),x+159,y+145,49f,align=Paint.Align.CENTER,number=true,bold=true)
        text("%.1f > %.2f".format(Locale.US,c.constant,score.rating/100.0),x+12,y+201,22f,muted,number=true)
        val chip = when(score.status) { "AD" -> "#a68c21"; "FC" -> "#087f9f"; else -> "#46425f" }
        polygon(chip,x+235,y+197,x+316,y+197,x+316,y+230,x+217,y+230)
        text(score.status,x+307,y+201,22f,align=Paint.Align.RIGHT,bold=true)
        return cover == null
    }

    fun render(snapshot: Snapshot): Rendered {
        val bitmap = Bitmap.createBitmap(1800,3120,Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap)
        paint.shader = LinearGradient(0f,0f,0f,3120f,Color.rgb(66,74,105),Color.rgb(39,13,82),Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,1800f,3120f,paint);paint.shader=null
        polygon("#304b4e88",0f,0f,1050f,0f,0f,1150f)
        polygon("#50110e41",720f,0f,1130f,0f,1800f,850f,1800f,1380f)
        polygon("#604d23a7",1540f,0f,1800f,0f,1800f,3120f,1410f,3120f,1780f,1640f)
        for(x in -3120..1800 step 260) line(x.toFloat(),0f,(x+3120).toFloat(),3120f,"#0ac0b5e8")
        text("范式：起源",72f,30f,83f,bold=true)
        text("P A R A D I G M :  R E B O O T",78f,133f,22f,muted)
        text("RATING BEST 50  //",1728f,83f,36f,align=Paint.Align.RIGHT)
        polygon("#20253d",72f,176f,1696f,176f,1728f,208f,1728f,328f,104f,328f,72f,296f)
        polygon("#303750",72f,176f,790f,176f,942f,328f,906f,328f,754f,176f)
        polygon("#655735",930f,176f,1696f,176f,1728f,208f,1728f,328f,1082f,328f)
        polygon("#71613c",1035f,176f,1240f,176f,1392f,328f,1187f,328f)
        line(930f,177f,1082f,327f,"#ebce7e",3f)
        line(1082f,327f,1726f,327f,"#ebce7e",3f)
        val avatar = snapshot.player.avatar?.let { BitmapFactory.decodeFile(it.path) }
        if(avatar!=null) { image(avatar,RectF(96f,192f,216f,312f));avatar.recycle() }
        else {
            rect(96f,192f,216f,312f,"#374560")
            text(snapshot.player.name.take(1),156f,219f,55f,align=Paint.Align.CENTER,bold=true)
        }
        line(94f,190f,218f,190f,"#b7bdcf");line(94f,314f,218f,314f,"#b7bdcf")
        line(94f,190f,94f,314f,"#b7bdcf");line(218f,190f,218f,314f,"#b7bdcf")
        text(snapshot.player.name,252f,188f,48f,bold=true,maxWidth=500f)
        text("#${snapshot.player.tag}",252f,257f,27f,muted,number=true)
        text("CN SERVER  /  BEST 50",252f,300f,17f,muted)
        text("PLAYER RATING",1173f,191f,24f,"#f0dca7")
        text("%.4f".format(Locale.US,snapshot.totalRating),1680f,225f,81f,align=Paint.Align.RIGHT,number=true,bold=true)
        text("本地计算 · 社区定数",1728f,333f,16f,muted,Paint.Align.RIGHT)
        section(356f,"B15 / 新曲","NEW BEST 15",snapshot.b15.sumOf { it.rating }/1500.0)
        var missing = 0
        snapshot.b15.forEachIndexed { i,s -> if(card(s,i+1,72f+(i%5)*334,442f+(i/5)*248))missing++ }
        section(1214f,"B35 / 旧曲","OLD BEST 35",snapshot.b35.sumOf { it.rating }/3500.0)
        snapshot.b35.forEachIndexed { i,s -> if(card(s,i+1,72f+(i%5)*334,1300f+(i/5)*248))missing++ }
        line(72f,3037f,1728f,3037f,"#636180")
        text("范式查查b  /  B50 = B35 + B15",72f,3060f,25f)
        val date = SimpleDateFormat("yyyy.MM.dd HH:mm",Locale.CHINA).apply { timeZone=TimeZone.getTimeZone("Asia/Shanghai") }
        text("候选 B50 · 非官方输出  |  数据 ${date.format(Date(snapshot.syncedAt))}",1728f,3056f,22f,muted,Paint.Align.RIGHT)
        val file = File(context.cacheDir,"reports/b50.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        return Rendered(file,missing)
    }
}
