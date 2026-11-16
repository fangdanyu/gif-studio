package com.gifstudio.editor

import com.gifstudio.editor.gif.GifDecoder
import com.gifstudio.editor.gif.GifEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GifCodecTest {

    private fun solid(w: Int, h: Int, color: Int): IntArray = IntArray(w * h) { color }

    @Test
    fun encodeDecode_solidFrames() {
        val w = 64
        val h = 48
        val frames = listOf(solid(w, h, 0xFFFF0000.toInt()), solid(w, h, 0xFF00FF00.toInt()))
        val bytes = GifEncoder.encode(w, h, frames, intArrayOf(5, 10), 0, 256)
        assertTrue("bytes should be produced", bytes.size > 20)
        val decoded = GifDecoder.decode(bytes, 1, 10, 10_000_000)
        assertNotNull(decoded)
        decoded!!
        assertEquals(2, decoded.frames.size)
        assertEquals(w, decoded.width)
        assertEquals(h, decoded.height)
        assertEquals(50, decoded.delaysMs[0])
        assertEquals(100, decoded.delaysMs[1])
        val p = decoded.frames[0][0]
        assertTrue("red channel", abs(((p shr 16) and 0xFF) - 255) <= 8)
        val g = decoded.frames[1][0]
        assertTrue("green channel", abs(((g shr 8) and 0xFF) - 255) <= 8)
    }

    @Test
    fun encodeDecode_gradientForcesCodeGrowth() {
        val w = 320
        val h = 240
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (0xFF shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or ((x + y) % 256)
        }
        val bytes = GifEncoder.encode(w, h, listOf(pixels), intArrayOf(10), 0, 256)
        val decoded = GifDecoder.decode(bytes, 1, 2, 10_000_000)
        assertNotNull(decoded)
        val out = decoded!!.frames[0]
        assertEquals(w * h, out.size)
        // 抽查若干像素的平均误差
        var err = 0
        var n = 0
        for (i in pixels.indices step 37) {
            val a = pixels[i]
            val b = out[i]
            err += abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
            err += abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
            err += abs((a and 0xFF) - (b and 0xFF))
            n += 3
        }
        assertTrue("average channel error too big: ${err / n}", err / n < 24)
    }

    @Test
    fun encodeDecode_transparency() {
        val w = 40
        val h = 40
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            px[y * w + x] = if (x < w / 2) 0x00000000 else 0xFF00A0FF.toInt()
        }
        val bytes = GifEncoder.encode(w, h, listOf(px), intArrayOf(10), 0, 256)
        val decoded = GifDecoder.decode(bytes, 1, 2, 10_000_000)
        assertNotNull(decoded)
        val out = decoded!!.frames[0]
        assertEquals(0, out[0] ushr 24)
        assertEquals(255, out[w / 2 + 2] ushr 24)
    }

    @Test
    fun probeSize_readsHeader() {
        val bytes = GifEncoder.encode(9, 7, listOf(solid(9, 7, 0xFF123456.toInt())), intArrayOf(4), 0, 32)
        val size = GifDecoder.probeSize(bytes)
        assertNotNull(size)
        assertEquals(9, size!![0])
        assertEquals(7, size[1])
    }

    @Test
    fun encodeDecode_lowColorPalette() {
        val w = 32
        val h = 32
        val px = IntArray(w * h) { if (it % 3 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val bytes = GifEncoder.encode(w, h, listOf(px), intArrayOf(10), 0, 4)
        val decoded = GifDecoder.decode(bytes, 1, 2, 1_000_000)
        assertNotNull(decoded)
        val out = decoded!!.frames[0]
        assertTrue((out[0] and 0xFF) < 40 || (out[0] and 0xFF) > 215)
    }
}
