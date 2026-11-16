package com.gifstudio.editor.model

import android.graphics.Bitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 内置滤镜 */
enum class FilterPreset(val label: String) {
    NONE("原图"),
    MONO("黑白"),
    SEPIA("复古"),
    WARM("暖调"),
    COOL("冷调"),
    FILM("胶片"),
    INVERT("反色"),
    CONTRAST("高对比"),
    FADE("淡雅")
}

/** 文字图层（坐标为相对于画面的 0~1 归一化中心点） */
data class TextOverlay(
    var text: String = "",
    var cx: Float = 0.5f,
    var cy: Float = 0.82f,
    var sizeRatio: Float = 0.10f,
    var color: Int = 0xFFFFFFFF.toInt(),
    var strokeColor: Int = 0xFF000000.toInt(),
    var style: Int = 0, // 0 描边白字 1 纯色 2 底色块
    var rotation: Float = 0f
)

/** 贴纸图层（内置表情贴纸），rotation 为角度制 */
data class StickerOverlay(
    var emoji: String = "⭐",
    var cx: Float = 0.5f,
    var cy: Float = 0.3f,
    var sizeRatio: Float = 0.18f,
    var rotation: Float = 0f
)

/** 自定义图片贴纸：sizeRatio 为高度占画面高度的比例，宽度按图片比例自动 */
data class ImageOverlay(
    val bitmap: Bitmap,
    var cx: Float = 0.5f,
    var cy: Float = 0.4f,
    var sizeRatio: Float = 0.3f,
    var rotation: Float = 0f
) {
    val aspect: Float get() = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
}

/** 涂鸦笔迹：points 为归一化坐标序列 x1,y1,x2,y2…（0~1），widthRatio 相对画面高度 */
data class DoodleStroke(
    val points: MutableList<Float> = mutableListOf(),
    var color: Int = 0xFFFF3B30.toInt(),
    var widthRatio: Float = 0.02f
)

/**
 * GIF 编辑项目。原始帧保持不动，所有裁剪/缩放/滤镜/文字都在渲染阶段生效，
 * 因此编辑随时可改、非破坏性。
 */
class GifProject {

    val sourceFrames = mutableListOf<Bitmap>()
    val sourceDelays = mutableListOf<Int>() // 毫秒

    var loopCount = 0

    // 归一化裁剪区域
    var cropLeft = 0f
    var cropTop = 0f
    var cropRight = 1f
    var cropBottom = 1f

    var speed = 1f
    var reversed = false

    /** 0 表示按裁剪区域自动决定输出尺寸 */
    var outWidth = 0
    var outHeight = 0

    var filter = FilterPreset.NONE

    val texts = mutableListOf<TextOverlay>()
    val stickers = mutableListOf<StickerOverlay>()
    val images = mutableListOf<ImageOverlay>()
    val doodles = mutableListOf<DoodleStroke>()

    var name: String = defaultName()

    val frameCount: Int get() = sourceFrames.size

    fun sourceIndexOf(logical: Int): Int {
        if (sourceFrames.isEmpty()) return 0
        val safe = logical.coerceIn(0, sourceFrames.size - 1)
        return if (reversed) sourceFrames.size - 1 - safe else safe
    }

    fun delayOf(logical: Int): Int {
        if (sourceDelays.isEmpty()) return 100
        val idx = sourceIndexOf(logical).coerceIn(0, sourceDelays.size - 1)
        return (sourceDelays[idx] / speed).toInt().coerceIn(20, 3000)
    }

    fun resetCrop() {
        cropLeft = 0f
        cropTop = 0f
        cropRight = 1f
        cropBottom = 1f
    }

    /** 普通图片合成时的默认帧时长 */
    fun setUniformDelay(ms: Int) {
        for (i in sourceDelays.indices) sourceDelays[i] = ms
    }

    fun hasOverlay(): Boolean =
        texts.isNotEmpty() || stickers.isNotEmpty() || images.isNotEmpty() || doodles.isNotEmpty()

    fun clearOverlays() {
        texts.clear()
        stickers.clear()
        images.clear()
        doodles.clear()
    }

    /** 删除若干帧（逻辑下标），至少保留 1 帧 */
    fun removeFrames(logicalIndexes: Collection<Int>) {
        if (sourceFrames.size <= 1) return
        val targets = logicalIndexes
            .map { sourceIndexOf(it) }
            .filter { it in sourceFrames.indices }
            .distinct()
            .sortedDescending()
        if (targets.isEmpty()) return
        val maxDeletable = sourceFrames.size - 1
        if (maxDeletable <= 0) return
        for (idx in targets.take(maxDeletable)) {
            sourceFrames.removeAt(idx)
            if (idx < sourceDelays.size) sourceDelays.removeAt(idx)
        }
    }

    fun copyMetadataFrom(other: GifProject) {
        texts.clear(); texts.addAll(other.texts)
        stickers.clear(); stickers.addAll(other.stickers)
        images.clear(); images.addAll(other.images)
        doodles.clear(); doodles.addAll(other.doodles)
        filter = other.filter
        speed = other.speed
        reversed = other.reversed
        cropLeft = other.cropLeft
        cropTop = other.cropTop
        cropRight = other.cropRight
        cropBottom = other.cropBottom
        outWidth = other.outWidth
        outHeight = other.outHeight
        name = other.name
    }

    companion object {
        fun defaultName(): String {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            return "GIF_Studio_${sdf.format(Date())}"
        }
    }
}
