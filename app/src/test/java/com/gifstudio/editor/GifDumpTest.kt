package com.gifstudio.editor

import com.gifstudio.editor.gif.GifDecoder
import com.gifstudio.editor.gif.GifEncoder
import org.junit.Test
import java.io.File

/** 生成可视化产物，供外部工具（PIL/浏览器）校验编码结果 */
class GifDumpTest {

    @Test
    fun dumpGradientGif() {
        val w = 200
        val h = 150
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (0xFF shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or 128
        }
        val bytes = GifEncoder.encode(w, h, listOf(px), IntArray(1) { 10 }, 0, 256)
        val out = File("build/gradient.gif")
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        val d = GifDecoder.decode(bytes, 1, 2, 10_000_000)!!
        var err = 0L
        var n = 0
        for (i in px.indices) {
            val a = px[i]; val b = d.frames[0][i]
            err += kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
            err += kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
            err += kotlin.math.abs((a and 0xFF) - (b and 0xFF))
            n += 3
        }
        println("GRADIENT size=${bytes.size} meanErr=${err / n}")
    }

    /** 校验解码器对「第三方编码器」产物的兼容性（build/ref.gif 由 Python PIL 生成） */
    @Test
    fun decodeReferencePilGif() {
        val f = File("build/ref.gif")
        if (!f.exists()) {
            println("REF missing, skip")
            return
        }
        val d = GifDecoder.decode(f.readBytes(), 1, 10, 10_000_000)!!
        println("REF frames=${d.frames.size} delays=${d.delaysMs.toList()} loop=${d.loopCount} size=${d.width}x${d.height}")
        val expected = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        for (i in 0 until minOf(d.frames.size, expected.size)) {
            var mismatch = 0
            for (p in d.frames[i]) {
                val dr = kotlin.math.abs(((p shr 16) and 0xFF) - ((expected[i] shr 16) and 0xFF))
                val dg = kotlin.math.abs(((p shr 8) and 0xFF) - ((expected[i] shr 8) and 0xFF))
                val db = kotlin.math.abs((p and 0xFF) - (expected[i] and 0xFF))
                if (dr > 8 || dg > 8 || db > 8 || (p ushr 24) < 200) mismatch++
            }
            println("REF frame$i mismatch=$mismatch of ${d.frames[i].size}")
        }
    }

    /** 用我们的解码器读 PIL 写的 256 色渐变图，验证是否与标准编码兼容 */
    @Test
    fun decodeReferenceGradient() {
        val f = File("build/refgrad.gif")
        if (!f.exists()) {
            println("REFGRAD missing, skip")
            return
        }
        val w = 200
        val h = 150
        val d = GifDecoder.decode(f.readBytes(), 1, 2, 10_000_000)!!
        var err = 0L
        var n = 0
        for (y in 0 until h) for (x in 0 until w) {
            val p = d.frames[0][y * w + x]
            val er = x * 255 / w
            val eg = y * 255 / h
            err += kotlin.math.abs(((p shr 16) and 0xFF) - er)
            err += kotlin.math.abs(((p shr 8) and 0xFF) - eg)
            err += kotlin.math.abs((p and 0xFF) - 128)
            n += 3
        }
        println("REFGRAD meanErr=${err / n} size=${d.width}x${d.height}")
    }

    @Test
    fun dumpEncodedGif() {
        val w = 64
        val h = 48
        val red = IntArray(w * h) { 0xFFFF0000.toInt() }
        val green = IntArray(w * h) { 0xFF00FF00.toInt() }
        val frames = listOf(red, green, red)
        val bytes = GifEncoder.encode(w, h, frames, intArrayOf(5, 10, 20), 0, 256)
        val out = File("build/inspect.gif")
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        println("WROTE ${out.absolutePath} size=${bytes.size}")
        println("HEADER: " + bytes.take(30).joinToString(" ") { String.format("%02X", it) })

        val d = GifDecoder.decode(bytes, 1, 10, 10_000_000)
        println("DECODED frames=${d?.frames?.size} delays=${d?.delaysMs?.toList()} w=${d?.width} h=${d?.height}")
        d?.frames?.forEachIndexed { i, px ->
            println("  frame$i p0=0x" + String.format("%08X", px[0]))
        }
    }
}
