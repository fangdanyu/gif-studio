package com.gifstudio.editor.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.gifstudio.editor.model.FilterPreset
import com.gifstudio.editor.model.GifProject
import com.gifstudio.editor.model.StickerOverlay
import com.gifstudio.editor.model.TextOverlay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ProjectRenderer {

    /** 按裁剪比例与自定义尺寸计算输出像素尺寸 */
    fun outputSize(p: GifProject, maxEdge: Int? = null): IntArray {
        val first = p.sourceFrames.firstOrNull() ?: return intArrayOf(1, 1)
        val srcW = first.width.toFloat()
        val srcH = first.height.toFloat()
        val cropW = max(1f, (p.cropRight - p.cropLeft) * srcW)
        val cropH = max(1f, (p.cropBottom - p.cropTop) * srcH)
        var w: Float = if (p.outWidth > 0) p.outWidth.toFloat() else cropW
        var h: Float = if (p.outWidth > 0 && p.outHeight > 0) {
            p.outHeight.toFloat()
        } else if (p.outWidth > 0) {
            p.outWidth * cropH / cropW
        } else {
            cropH
        }
        if (maxEdge != null) {
            val m = max(w, h)
            if (m > maxEdge) {
                val k = maxEdge.toFloat() / m
                w *= k
                h *= k
            }
        }
        return intArrayOf(max(2, w.roundToInt()), max(2, h.roundToInt()))
    }

    /** 只渲染画面（裁剪 + 缩放 + 滤镜），不含文字/贴纸 */
    fun renderBase(p: GifProject, logicalIndex: Int, width: Int, height: Int): Bitmap {
        val src = p.sourceFrames[p.sourceIndexOf(logicalIndex)]
        val out = Bitmap.createBitmap(max(2, width), max(2, height), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(0x00000000)
        val srcRect = Rect(
            (p.cropLeft * src.width).toInt().coerceIn(0, src.width - 1),
            (p.cropTop * src.height).toInt().coerceIn(0, src.height - 1),
            (p.cropRight * src.width).toInt().coerceIn(1, src.width),
            (p.cropBottom * src.height).toInt().coerceIn(1, src.height)
        )
        val dstRect = RectF(0f, 0f, out.width.toFloat(), out.height.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = filterOf(p.filter)
        canvas.drawBitmap(src, srcRect, dstRect, paint)
        return out
    }

    /** 把涂鸦、图片贴纸、表情贴纸与文字画到画布（预览与导出共用，保证所见即所得） */
    fun drawOverlays(canvas: Canvas, p: GifProject, width: Float, height: Float) {
        drawDoodles(canvas, p, width, height)
        for (img in p.images) drawImage(canvas, img, width, height)
        for (s in p.stickers) drawSticker(canvas, s, width, height)
        for (t in p.texts) drawText(canvas, t, width, height)
    }

    /** 图层尺寸（像素）：返回 宽×高 */
    fun imageSizeInPx(img: com.gifstudio.editor.model.ImageOverlay, height: Float): Pair<Float, Float> {
        val h = img.sizeRatio * height
        return (h * img.aspect) to h
    }

    fun drawImage(
        canvas: Canvas,
        img: com.gifstudio.editor.model.ImageOverlay,
        width: Float,
        height: Float
    ) {
        if (img.bitmap.isRecycled) return
        val (w, h) = imageSizeInPx(img, height)
        val cx = img.cx * width
        val cy = img.cy * height
        canvas.save()
        if (img.rotation != 0f) canvas.rotate(img.rotation, cx, cy)
        val dst = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(img.bitmap, null, dst, paint)
        canvas.restore()
    }

    /** 涂鸦笔迹（归一化坐标，线宽相对画面高度） */
    fun drawDoodles(canvas: Canvas, p: GifProject, width: Float, height: Float) {
        for (d in p.doodles) {
            if (d.points.size < 4) {
                if (d.points.size == 2) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = d.color
                        style = Paint.Style.FILL
                    }
                    canvas.drawCircle(
                        d.points[0] * width, d.points[1] * height,
                        (d.widthRatio * height) / 2f, paint
                    )
                }
                continue
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = d.color
                style = Paint.Style.STROKE
                strokeWidth = (d.widthRatio * height).coerceAtLeast(1f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val path = android.graphics.Path()
            path.moveTo(d.points[0] * width, d.points[1] * height)
            var i = 2
            while (i + 1 < d.points.size) {
                path.lineTo(d.points[i] * width, d.points[i + 1] * height)
                i += 2
            }
            canvas.drawPath(path, paint)
        }
    }

    fun drawSticker(canvas: Canvas, s: StickerOverlay, width: Float, height: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = s.sizeRatio * height
        paint.textAlign = Paint.Align.CENTER
        val cx = s.cx * width
        val cy = s.cy * height - (paint.descent() + paint.ascent()) / 2f
        canvas.save()
        if (s.rotation != 0f) canvas.rotate(s.rotation, s.cx * width, s.cy * height)
        canvas.drawText(s.emoji, cx, cy, paint)
        canvas.restore()
    }

    fun drawText(canvas: Canvas, t: TextOverlay, width: Float, height: Float) {
        if (t.text.isBlank()) return
        val size = t.sizeRatio * height
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = size
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        val metrics = paint.fontMetrics
        val baseY = t.cy * height - (metrics.ascent + metrics.descent) / 2f
        canvas.save()
        if (t.rotation != 0f) canvas.rotate(t.rotation, t.cx * width, t.cy * height)
        when (t.style) {
            0 -> {
                paint.style = Paint.Style.FILL
                paint.color = t.strokeColor
                // 用描边多方向偏移模拟描边
                val w = size * 0.06f
                for (dx in intArrayOf(-1, 0, 1)) {
                    for (dy in intArrayOf(-1, 0, 1)) {
                        if (dx == 0 && dy == 0) continue
                        canvas.drawText(t.text, t.cx * width + dx * w, baseY + dy * w, paint)
                    }
                }
                paint.color = t.color
                canvas.drawText(t.text, t.cx * width, baseY, paint)
            }
            1 -> {
                paint.color = t.color
                canvas.drawText(t.text, t.cx * width, baseY, paint)
            }
            else -> {
                val tw = paint.measureText(t.text)
                val padX = size * 0.35f
                val padY = size * 0.22f
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                bgPaint.color = t.strokeColor
                val rect = RectF(
                    t.cx * width - tw / 2f - padX,
                    t.cy * height - size / 2f - padY,
                    t.cx * width + tw / 2f + padX,
                    t.cy * height + size / 2f + padY
                )
                canvas.drawRoundRect(rect, size * 0.25f, size * 0.25f, bgPaint)
                paint.color = t.color
                canvas.drawText(t.text, t.cx * width, baseY, paint)
            }
        }
        canvas.restore()
    }

    fun filterOf(preset: FilterPreset): ColorFilter? = when (preset) {
        FilterPreset.NONE -> null
        FilterPreset.MONO -> ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        FilterPreset.SEPIA -> ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0.9f, 0.35f, 0.1f, 0f, 12f,
                    0.7f, 0.65f, 0.1f, 0f, 4f,
                    0.5f, 0.45f, 0.35f, 0f, -8f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        FilterPreset.WARM -> ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    1.12f, 0f, 0f, 0f, 12f,
                    0f, 1.02f, 0f, 0f, 4f,
                    0f, 0f, 0.88f, 0f, -6f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        FilterPreset.COOL -> ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0.88f, 0f, 0f, 0f, -6f,
                    0f, 1.0f, 0f, 0f, 2f,
                    0f, 0f, 1.14f, 0f, 14f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        FilterPreset.FILM -> ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    1.05f, 0.02f, 0.05f, 0f, -4f,
                    0.02f, 1.02f, 0.04f, 0f, 2f,
                    0.05f, 0.02f, 0.95f, 0f, 6f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        FilterPreset.INVERT -> ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        FilterPreset.CONTRAST -> ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    1.35f, 0f, 0f, 0f, -40f,
                    0f, 1.35f, 0f, 0f, -40f,
                    0f, 0f, 1.35f, 0f, -40f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        FilterPreset.FADE -> ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0.92f, 0f, 0f, 0f, 22f,
                    0f, 0.92f, 0f, 0f, 22f,
                    0f, 0f, 0.92f, 0f, 26f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }

    /** 导出用：渲染成 ARGB 像素数组 */
    fun renderPixels(p: GifProject, logicalIndex: Int, width: Int, height: Int): IntArray {
        val bmp = renderBase(p, logicalIndex, width, height)
        val canvas = Canvas(bmp)
        drawOverlays(canvas, p, bmp.width.toFloat(), bmp.height.toFloat())
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()
        return pixels
    }

    /** 缩略帧（用于帧列表） */
    fun renderThumb(p: GifProject, logicalIndex: Int, maxEdge: Int = 96): Bitmap {
        val size = outputSize(p, maxEdge)
        val bmp = renderBase(p, logicalIndex, size[0], size[1])
        val canvas = Canvas(bmp)
        drawOverlays(canvas, p, bmp.width.toFloat(), bmp.height.toFloat())
        return bmp
    }

    fun clampOverlay(v: Float): Float = min(1f, max(0f, v))
}
