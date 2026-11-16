package com.gifstudio.editor.gif

import java.io.ByteArrayOutputStream

/**
 * 极简 GIF89a 编码器，纯 JVM 实现（无 android 依赖，可单测）。
 * 每帧使用独立局部调色板，支持 1bit 透明通道与循环次数设置。
 */
object GifEncoder {

    /**
     * @param frames 每帧 ARGB 像素数组，长度必须为 width*height
     * @param delaysCentis 每帧延时（1/100 秒），<=0 时按 1 处理
     * @param loopCount 循环次数，0 表示无限循环
     * @param maxColors 调色板上限（2..256），值越大画质越好、体积越大
     */
    fun encode(
        width: Int,
        height: Int,
        frames: List<IntArray>,
        delaysCentis: IntArray,
        loopCount: Int = 0,
        maxColors: Int = 256
    ): ByteArray {
        require(width > 0 && height > 0) { "invalid size" }
        require(frames.isNotEmpty()) { "no frames" }
        require(delaysCentis.size >= frames.size) { "delays size mismatch" }
        for ((i, f) in frames.withIndex()) {
            require(f.size == width * height) { "frame $i size mismatch" }
        }

        val out = ByteArrayOutputStream(1024 * 64)
        // Header
        out.write(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)) // GIF89a
        // Logical Screen Descriptor
        writeShort(out, width)
        writeShort(out, height)
        out.write(0x70) // 无全局色表, color resolution 7
        out.write(0)    // 背景色索引
        out.write(0)    // 像素宽高比
        // NETSCAPE 循环扩展
        out.write(0x21)
        out.write(0xFF)
        out.write(0x0B)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(0x03)
        out.write(0x01)
        writeShort(out, loopCount.coerceIn(0, 65535))
        out.write(0x00)

        for (i in frames.indices) {
            val q = GifQuantizer.quantize(frames[i], maxColors)
            val delay = delaysCentis[i].coerceIn(1, 65535)
            val transparent = q.transparentIndex >= 0
            val disposal = if (transparent) 2 else 1
            // GCE
            out.write(0x21)
            out.write(0xF9)
            out.write(0x04)
            out.write((disposal shl 2) or (if (transparent) 1 else 0))
            writeShort(out, delay)
            out.write(if (transparent) q.transparentIndex else 0)
            out.write(0x00)
            // Image Descriptor（每帧铺满画布，简化合成）
            out.write(0x2C)
            writeShort(out, 0)
            writeShort(out, 0)
            writeShort(out, width)
            writeShort(out, height)
            val sizeBits = log2(q.tableSize) - 1
            out.write(0x80 or (sizeBits and 0x07)) // 局部色表标志
            // 局部色表
            for (c in q.palette) {
                out.write((c shr 16) and 0xFF)
                out.write((c shr 8) and 0xFF)
                out.write(c and 0xFF)
            }
            // LZW 数据
            writeLzw(out, q.indices, maxOf(2, log2(q.tableSize)))
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    private fun log2(v: Int): Int {
        var n = 0
        var x = v
        while (x > 1) {
            x = x shr 1
            n++
        }
        return n
    }

    private fun writeShort(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun writeLzw(out: ByteArrayOutputStream, indices: IntArray, minCodeSize: Int) {
        // GIF 规范：LZW 数据前必须先写 1 字节最小码长
        out.write(minCodeSize)
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        val bitWriter = BitWriter()
        val dict = HashMap<Int, Int>(4096)

        var next = eoi + 1
        var codeSize = minCodeSize + 1
        var maxCode = (1 shl codeSize) - 1

        // 按标准实现：先写码，写完后按当前词条数决定是否扩位（与解码端保持同步）
        fun emit(code: Int) {
            bitWriter.write(code, codeSize)
            if (next > maxCode && codeSize < 12) {
                codeSize++
                maxCode = (1 shl codeSize) - 1
            }
        }

        emit(clear)

        if (indices.isNotEmpty()) {
            var ent = indices[0]
            var i = 1
            while (i < indices.size) {
                val c = indices[i]
                i++
                val key = (ent shl 8) or c
                val existing = dict[key]
                if (existing != null) {
                    ent = existing
                    continue
                }
                emit(ent)
                ent = c
                if (next < 4096) {
                    dict[key] = next
                    next++
                } else {
                    // 码表已满，发清除码并重置
                    emit(clear)
                    dict.clear()
                    next = eoi + 1
                    codeSize = minCodeSize + 1
                    maxCode = (1 shl codeSize) - 1
                }
            }
            emit(ent)
        }
        emit(eoi)
        bitWriter.finish()
        flushBlocks(out, bitWriter)
    }

    private fun flushBlocks(out: ByteArrayOutputStream, writer: BitWriter) {
        val data = writer.toByteArray()
        var pos = 0
        while (pos < data.size) {
            val n = minOf(255, data.size - pos)
            out.write(n)
            out.write(data, pos, n)
            pos += n
        }
        out.write(0x00) // block terminator
    }

    /** LSB 优先的位流写入器（自有缓冲，最后按 255 字节切子块） */
    private class BitWriter {
        private val buffer = ByteArrayOutputStream(4096)
        private var cur = 0
        private var bits = 0

        fun write(code: Int, size: Int) {
            cur = cur or (code shl bits)
            bits += size
            while (bits >= 8) {
                buffer.write(cur and 0xFF)
                cur = cur ushr 8
                bits -= 8
            }
        }

        fun finish() {
            if (bits > 0) {
                buffer.write(cur and 0xFF)
                cur = 0
                bits = 0
            }
        }

        fun toByteArray(): ByteArray = buffer.toByteArray()
    }
}
