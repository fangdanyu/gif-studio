package com.gifstudio.editor.media

import com.gifstudio.editor.edit.ProjectRenderer
import com.gifstudio.editor.gif.GifEncoder
import com.gifstudio.editor.model.GifProject
import kotlin.math.min

/** 导出编码：质量分档、按目标体积自适应、循环次数、进度回调 */
object ExportEngine {

    enum class Quality(val label: String, val desc: String, val maxEdge: Int, val colors: Int, val frameStep: Int) {
        HD("高清", "全帧 · 256色 · 长边≤720", 720, 256, 1),
        STD("标准", "128色 · 长边≤480", 480, 128, 1),
        SMALL("压缩", "64色 · 长边≤320 · 抽帧", 320, 64, 2)
    }

    /** 按文件大小导出时使用的参数组合（由好到差） */
    private data class Attempt(val maxEdge: Int, val colors: Int, val frameStep: Int)

    private val sizeLadder = listOf(
        Attempt(720, 256, 1),
        Attempt(720, 192, 1),
        Attempt(640, 160, 1),
        Attempt(560, 128, 1),
        Attempt(480, 128, 1),
        Attempt(480, 96, 2),
        Attempt(400, 96, 2),
        Attempt(360, 64, 2),
        Attempt(320, 64, 3),
        Attempt(260, 48, 3),
        Attempt(220, 32, 4),
        Attempt(180, 24, 5)
    )

    fun export(
        p: GifProject,
        quality: Quality,
        loopCount: Int = p.loopCount,
        onProgress: ((Int) -> Unit)? = null
    ): ByteArray = encodeWith(p, quality.maxEdge, quality.colors, quality.frameStep, loopCount, onProgress)

    /**
     * 按目标文件体积导出：从高质量参数开始逐级降档，直到编码结果 ≤ targetBytes。
     * 全部档位都超标时返回最后一档（体积最小）的结果。
     */
    fun exportWithinSize(
        p: GifProject,
        targetBytes: Long,
        loopCount: Int = p.loopCount,
        onProgress: ((Int) -> Unit)? = null
    ): ByteArray {
        var best: ByteArray? = null
        for ((i, a) in sizeLadder.withIndex()) {
            val bytes = encodeWith(p, a.maxEdge, a.colors, a.frameStep, loopCount) { pct ->
                // 每个档位占总进度的 1/sizeLadder.size
                onProgress?.invoke(((i * 100 + pct) / sizeLadder.size).coerceIn(0, 99))
            }
            best = bytes
            if (bytes.size.toLong() <= targetBytes) {
                onProgress?.invoke(95)
                return bytes
            }
        }
        onProgress?.invoke(95)
        return best ?: ByteArray(0)
    }

    private fun encodeWith(
        p: GifProject,
        maxEdge: Int,
        colors: Int,
        frameStep: Int,
        loopCount: Int,
        onProgress: ((Int) -> Unit)? = null
    ): ByteArray {
        val size = ProjectRenderer.outputSize(p, maxEdge)
        val w = size[0]
        val h = size[1]
        val count = p.frameCount
        if (count == 0) throw IllegalStateException("没有可导出的帧")

        var step = frameStep
        while (count / step > 40) step++

        val picked = ArrayList<Int>()
        var i = 0
        while (i < count) {
            picked.add(i)
            i += step
        }
        if (picked.isEmpty()) picked.add(0)

        val frames = ArrayList<IntArray>(picked.size)
        val delays = IntArray(picked.size)
        for ((k, logical) in picked.withIndex()) {
            frames.add(ProjectRenderer.renderPixels(p, logical, w, h))
            var ms = 0
            var j = logical
            val end = min(count, logical + step)
            while (j < end) {
                ms += p.delayOf(j)
                j++
            }
            delays[k] = (ms / 10).coerceIn(2, 6000) // 1/100 秒
            onProgress?.invoke(((k + 1) * 90) / picked.size)
        }
        val bytes = GifEncoder.encode(w, h, frames, delays, loopCount, colors)
        onProgress?.invoke(95)
        return bytes
    }
}
