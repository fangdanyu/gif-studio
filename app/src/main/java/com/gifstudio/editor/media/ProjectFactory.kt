package com.gifstudio.editor.media

import android.content.Context
import android.net.Uri
import com.gifstudio.editor.model.FilterPreset
import com.gifstudio.editor.model.GifProject
import com.gifstudio.editor.model.StickerOverlay
import com.gifstudio.editor.model.TextOverlay

/** 各种来源 → 可编辑 GifProject */
object ProjectFactory {

    fun fromImages(ctx: Context, uris: List<Uri>, delayMs: Int = 200): GifProject? {
        val frames = ArrayList<android.graphics.Bitmap>()
        for (u in uris) {
            val bmp = MediaLoader.loadBitmap(ctx, u) ?: continue
            frames.add(bmp)
            if (frames.size >= 30) break
        }
        if (frames.isEmpty()) return null
        val p = GifProject()
        p.sourceFrames.addAll(frames)
        repeat(frames.size) { p.sourceDelays.add(delayMs) }
        return p
    }

    fun fromVideo(
        ctx: Context,
        uri: Uri,
        startMs: Long,
        durationMs: Long,
        fps: Int,
        maxEdge: Int,
        onProgress: ((Int) -> Unit)? = null
    ): GifProject? {
        val frames = MediaLoader.videoFrames(
            ctx, uri, startMs, durationMs, fps, maxEdge, 40, onProgress
        )
        if (frames.isEmpty()) return null
        val p = GifProject()
        p.sourceFrames.addAll(frames)
        repeat(frames.size) { p.sourceDelays.add((1000 / fps).coerceAtLeast(20)) }
        return p
    }

    fun fromGif(ctx: Context, uri: Uri): GifProject? = MediaLoader.loadGifProject(ctx, uri)

    /** 套用表情包模板 */
    fun applyTemplate(p: GifProject, template: GifTemplate) {
        p.filter = template.filter
        p.stickers.clear()
        p.texts.clear()
        template.sticker?.let { p.stickers.add(StickerOverlay(it, cx = 0.5f, cy = 0.24f, sizeRatio = 0.24f)) }
        p.texts.add(
            TextOverlay(
                text = template.caption,
                cx = 0.5f,
                cy = 0.78f,
                sizeRatio = template.textSize,
                color = template.textColor,
                style = template.textStyle
            )
        )
    }
}

/** 内置表情包模板 */
data class GifTemplate(
    val id: String,
    val title: String,
    val desc: String,
    val emoji: String,
    val sticker: String?,
    val caption: String,
    val textColor: Int,
    val textStyle: Int,
    val textSize: Float,
    val filter: FilterPreset,
    val gradientStart: Int,
    val gradientEnd: Int
)

object Templates {
    val all = listOf(
        GifTemplate(
            "worker", "打工人日常", "自嘲式吐槽文字", "😩", "😩",
            "又是搬砖的一天", 0xFFFFFFFF.toInt(), 0, 0.11f, FilterPreset.FADE,
            0xFFFF8A3D.toInt(), 0xFFFF4E6B.toInt()
        ),
        GifTemplate(
            "shock", "震惊体", "大字报式标题", "😱", "😱",
            "震惊！这也行？", 0xFFFFD400.toInt(), 2, 0.13f, FilterPreset.CONTRAST,
            0xFF7C4DFF.toInt(), 0xFF3D7BFF.toInt()
        ),
        GifTemplate(
            "yummy", "真香现场", "美食必备", "🍜", "🍜",
            "真香！", 0xFFFFFFFF.toInt(), 0, 0.14f, FilterPreset.WARM,
            0xFFFFB03A.toInt(), 0xFFE2541F.toInt()
        ),
        GifTemplate(
            "fight", "加油打气", "群里鼓劲专用", "💪", "💪",
            "冲！你可以的", 0xFFFFFFFF.toInt(), 2, 0.11f, FilterPreset.NONE,
            0xFF12B7A4.toInt(), 0xFF3DBEFF.toInt()
        ),
        GifTemplate(
            "cute", "可爱贴贴", "卖萌表情包", "🤗", "🤗",
            "贴贴～", 0xFFFF8AB4.toInt(), 1, 0.13f, FilterPreset.FADE,
            0xFFFF9EC4.toInt(), 0xFFB15BFF.toInt()
        ),
        GifTemplate(
            "speechless", "无语子", "社交无语瞬间", "🙄", "🙄",
            "无语子", 0xFFFFFFFF.toInt(), 0, 0.14f, FilterPreset.MONO,
            0xFF5C6470.toInt(), 0xFF2A2E37.toInt()
        )
    )

    fun byId(id: String): GifTemplate? = all.firstOrNull { it.id == id }
}
