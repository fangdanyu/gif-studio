package com.gifstudio.editor

import com.gifstudio.editor.gif.GifDecoder
import com.gifstudio.editor.gif.GifEncoder
import org.junit.Test

/**
 * 编码性能基准：30 帧 480×480、256 色（对应「高清」档上限）。
 * 仅打印耗时与体积，不做硬性断言，数据用于 docs/测试记录.md。
 */
class GifPerformanceTest {

    @Test
    fun benchmark30Frames480() {
        val w = 480
        val h = 480
        val frames = ArrayList<IntArray>(30)
        for (f in 0 until 30) {
            val px = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val r = (x * 255 / w + f * 8) and 0xFF
                    val g = (y * 255 / h) and 0xFF
                    val b = ((x + y + f * 4) / 2) and 0xFF
                    px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            frames.add(px)
        }
        val delays = IntArray(30) { 8 }

        // 预热
        GifEncoder.encode(w, h, frames.subList(0, 2), intArrayOf(8, 8), 0, 256)

        val t0 = System.nanoTime()
        val bytes = GifEncoder.encode(w, h, frames, delays, 0, 256)
        val encodeMs = (System.nanoTime() - t0) / 1_000_000

        val t1 = System.nanoTime()
        val decoded = GifDecoder.decode(bytes, 1, 10, 100_000_000L)
        val decodeMs = (System.nanoTime() - t1) / 1_000_000

        println("BENCH 30 frames 480x480 256colors")
        println("  encode = ${encodeMs} ms")
        println("  decode = ${decodeMs} ms")
        println("  gif size = ${bytes.size / 1024} KB")
        println("  frames decoded = ${decoded?.frames?.size}")
    }

    @Test
    fun benchmarkCompressLevel320() {
        val w = 320
        val h = 320
        val frames = ArrayList<IntArray>(20)
        for (f in 0 until 20) {
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val r = (x * 255 / w + f * 12) and 0xFF
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or ((y * 255 / h) shl 8) or 90
            }
            frames.add(px)
        }
        val t0 = System.nanoTime()
        val bytes = GifEncoder.encode(w, h, frames, IntArray(20) { 8 }, 0, 64)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("BENCH 压缩档 20 frames 320x320 64colors: encode=${ms}ms size=${bytes.size / 1024}KB")
    }
}
