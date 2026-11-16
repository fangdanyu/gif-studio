package com.gifstudio.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.gifstudio.editor.gif.GifDecoder
import com.gifstudio.editor.model.GifProject
import kotlin.math.max
import kotlin.math.roundToInt

/** 相册/视频导入：统一做尺寸限制与方向矫正，防止内存爆炸 */
object MediaLoader {

    const val MAX_EDGE = 720
    const val MAX_FRAMES_FROM_GIF = 64

    fun loadBitmap(ctx: Context, uri: Uri, maxEdge: Int = MAX_EDGE): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            // EXIF 方向
            val orientation = try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees != 0f) {
                val m = Matrix().apply { postRotate(degrees) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated != bmp) bmp.recycle()
                bmp = rotated
            }
            scaleDown(bmp, maxEdge)
        } catch (e: Exception) {
            null
        }
    }

    fun scaleDown(bmp: Bitmap, maxEdge: Int): Bitmap {
        val m = max(bmp.width, bmp.height)
        if (m <= maxEdge) return bmp
        val k = maxEdge.toFloat() / m
        val w = (bmp.width * k).roundToInt().coerceAtLeast(2)
        val h = (bmp.height * k).roundToInt().coerceAtLeast(2)
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        if (scaled != bmp) bmp.recycle()
        return scaled
    }

    /** 读取 GIF 为可编辑项目 */
    fun loadGifProject(ctx: Context, uri: Uri, maxEdge: Int = 1024): GifProject? {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val size = GifDecoder.probeSize(bytes) ?: return null
        var scale = 1
        while (max(size[0], size[1]) / scale > maxEdge && scale < 8) scale *= 2
        val decoded = GifDecoder.decode(
            bytes, scale, MAX_FRAMES_FROM_GIF, 26_000_000L
        ) ?: return null
        val project = GifProject()
        for (pixels in decoded.frames) {
            val bmp = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
            project.sourceFrames.add(bmp)
        }
        for (d in decoded.delaysMs) project.sourceDelays.add(d)
        project.loopCount = decoded.loopCount
        return project
    }

    fun videoDurationMs(ctx: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ctx, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (e: Exception) { }
        }
    }

    /**
     * 从视频截取片段转为位图序列
     * @param fps 每秒抽取帧数
     */
    fun videoFrames(
        ctx: Context,
        uri: Uri,
        startMs: Long,
        durationMs: Long,
        fps: Int = 12,
        maxEdge: Int = MAX_EDGE,
        maxFrames: Int = 40,
        onProgress: ((Int) -> Unit)? = null
    ): List<Bitmap> {
        val frames = ArrayList<Bitmap>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(ctx, uri)
            val total = durationMs.coerceAtLeast(1L)
            val count = ((total / 1000f) * fps).toInt().coerceIn(1, maxFrames)
            for (i in 0 until count) {
                val tMs = startMs + (i * 1000L) / fps
                val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, maxEdge, maxEdge)
                        ?: retriever.getFrameAtTime(tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                } else {
                    retriever.getFrameAtTime(tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                }
                if (bmp != null) {
                    frames.add(scaleDown(bmp, maxEdge))
                }
                onProgress?.invoke(((i + 1) * 100) / count)
            }
        } catch (e: Exception) {
            // 忽略并返回已取到的帧
        } finally {
            try { retriever.release() } catch (e: Exception) { }
        }
        return frames
    }
}
