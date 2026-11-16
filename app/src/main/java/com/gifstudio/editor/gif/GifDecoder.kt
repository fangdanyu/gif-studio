package com.gifstudio.editor.gif

/**
 * GIF87a/89a 解码器，纯 JVM 实现（无 android 依赖，可单测）。
 * 支持：全局/局部调色板、LZW、隔行扫描、GCE 透明色、处置方式 0/1/2/3、降采样解码。
 */
class DecodedGif(
    val width: Int,
    val height: Int,
    /** 每帧 ARGB 像素数组（长度 width*height），透明像素 alpha=0 */
    val frames: List<IntArray>,
    /** 每帧延时（毫秒） */
    val delaysMs: IntArray,
    /** 循环次数，0 = 无限 */
    val loopCount: Int
)

object GifDecoder {

    /** 仅读取画布尺寸，用于决定解码降采样倍数 */
    fun probeSize(data: ByteArray): IntArray? {
        if (data.size < 10) return null
        if (data[0] != 0x47.toByte() || data[1] != 0x49.toByte() || data[2] != 0x46.toByte()) return null
        val w = readShort(data, 6)
        val h = readShort(data, 8)
        if (w <= 0 || h <= 0) return null
        return intArrayOf(w, h)
    }

    /**
     * @param scale 降采样倍数（1/2/4/8），用于控制内存
     * @param maxFrames 最多解码帧数
     * @param maxTotalPixels 输出总像素预算（宽*高*帧数）
     */
    fun decode(
        data: ByteArray,
        scale: Int = 1,
        maxFrames: Int = 64,
        maxTotalPixels: Long = 26_000_000L
    ): DecodedGif? {
        if (data.size < 13) return null
        if (data[0] != 0x47.toByte() || data[1] != 0x49.toByte() || data[2] != 0x46.toByte()) return null

        var pos = 6
        val sw = readShort(data, pos); pos += 2
        val sh = readShort(data, pos); pos += 2
        val packed = data[pos].toInt() and 0xFF; pos += 1
        pos += 2 // background + aspect
        if (sw <= 0 || sh <= 0) return null

        var globalTable: IntArray? = null
        if (packed and 0x80 != 0) {
            val size = 1 shl ((packed and 0x07) + 1)
            globalTable = readColorTable(data, pos, size)
            pos += size * 3
        }

        val s = scale.coerceIn(1, 8)
        val ow = maxOf(1, (sw + s - 1) / s)
        val oh = maxOf(1, (sh + s - 1) / s)
        val canvas = IntArray(sw * sh) // ARGB, 0 = 全透明

        val frames = ArrayList<IntArray>(maxFrames)
        val delays = ArrayList<Int>(maxFrames)
        var loop = 0

        // 当前 GCE 状态
        var disposal = 0
        var delayCs = 0
        var transIndex = -1
        var hasTrans = false

        while (pos < data.size) {
            val block = data[pos].toInt() and 0xFF
            pos += 1
            when (block) {
                0x3B -> return finish(frames, delays, loop, ow, oh) // trailer
                0x21 -> { // extension
                    if (pos >= data.size) break
                    val label = data[pos].toInt() and 0xFF
                    pos += 1
                    if (label == 0xF9) {
                        // Graphic Control Extension
                        val len = data[pos].toInt() and 0xFF
                        pos += 1
                        if (len >= 4) {
                            val flags = data[pos].toInt() and 0xFF
                            disposal = (flags shr 2) and 0x07
                            hasTrans = (flags and 0x01) != 0
                            delayCs = readShort(data, pos + 1)
                            transIndex = data[pos + 3].toInt() and 0xFF
                            pos += len
                        }
                        // 跳过数据子块直到 0
                        pos = skipSubBlocks(data, pos)
                    } else if (label == 0xFF) {
                        // Application Extension (NETSCAPE)
                        val len = data[pos].toInt() and 0xFF
                        pos += 1
                        val app = String(data, pos, minOf(len, 11), Charsets.US_ASCII)
                        pos += len
                        if (app.startsWith("NETSCAPE")) {
                            var p = pos
                            while (p < data.size) {
                                val n = data[p].toInt() and 0xFF
                                if (n == 0) { p += 1; break }
                                if (n >= 3 && data[p + 1].toInt() and 0xFF == 0x01) {
                                    loop = readShort(data, p + 2)
                                }
                                p += 1 + n
                            }
                            pos = p
                        } else {
                            pos = skipSubBlocks(data, pos)
                        }
                    } else {
                        pos = skipSubBlocks(data, pos)
                    }
                }
                0x2C -> { // image descriptor
                    if (pos + 9 > data.size) break
                    val left = readShort(data, pos); pos += 2
                    val top = readShort(data, pos); pos += 2
                    val iw = readShort(data, pos); pos += 2
                    val ih = readShort(data, pos); pos += 2
                    val flags = data[pos].toInt() and 0xFF; pos += 1
                    var table = globalTable
                    if (flags and 0x80 != 0) {
                        val size = 1 shl ((flags and 0x07) + 1)
                        table = readColorTable(data, pos, size)
                        pos += size * 3
                    }
                    val interlaced = (flags and 0x40) != 0
                    if (pos >= data.size) break
                    val minCodeSize = data[pos].toInt() and 0xFF
                    pos += 1
                    var compressed = ByteArray(0)
                    var p = pos
                    val chunks = java.io.ByteArrayOutputStream()
                    while (p < data.size) {
                        val n = data[p].toInt() and 0xFF
                        if (n == 0) { p += 1; break }
                        chunks.write(data, p + 1, minOf(n, data.size - (p + 1)))
                        p += 1 + n
                    }
                    pos = p
                    compressed = chunks.toByteArray()

                    if (table != null && iw > 0 && ih > 0) {
                        if (frames.size < maxFrames &&
                            (frames.size + 1).toLong() * ow.toLong() * oh.toLong() <= maxTotalPixels
                        ) {
                            // 需要恢复上一帧时先备份
                            val backup = if (disposal == 3) canvas.copyOf() else null
                            val pixels = lzwDecode(compressed, iw * ih, minCodeSize) ?: IntArray(iw * ih)
                            drawImage(
                                canvas, sw, sh, pixels, iw, ih, left, top, interlaced,
                                table, if (hasTrans) transIndex else -1
                            )
                            frames.add(downscale(canvas, sw, sh, ow, oh, s))
                            delays.add(maxOf(20, delayCs * 10))
                            // 处置
                            when (disposal) {
                                2 -> fillRect(canvas, sw, sh, left, top, iw, ih)
                                3 -> if (backup != null) System.arraycopy(backup, 0, canvas, 0, canvas.size)
                            }
                            disposal = 0
                            hasTrans = false
                            transIndex = -1
                            delayCs = 0
                        } else {
                            // 达上限，停止解码后续帧但仍需定位
                            break
                        }
                    }
                }
                0x00 -> { /* 填充字节，忽略 */ }
                else -> {
                    // 未知块，尝试跳过
                }
            }
        }
        return finish(frames, delays, loop, ow, oh)
    }

    private fun finish(frames: ArrayList<IntArray>, delays: ArrayList<Int>, loop: Int, ow: Int, oh: Int): DecodedGif? {
        if (frames.isEmpty()) return null
        return DecodedGif(ow, oh, frames, delays.toIntArray(), loop)
    }

    private fun readShort(d: ByteArray, p: Int): Int {
        if (p + 1 >= d.size) return 0
        return ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p].toInt() and 0xFF)
    }

    private fun readColorTable(d: ByteArray, pos: Int, size: Int): IntArray {
        val table = IntArray(size)
        var p = pos
        for (i in 0 until size) {
            if (p + 2 >= d.size) break
            val r = d[p].toInt() and 0xFF
            val g = d[p + 1].toInt() and 0xFF
            val b = d[p + 2].toInt() and 0xFF
            table[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            p += 3
        }
        return table
    }

    private fun skipSubBlocks(d: ByteArray, start: Int): Int {
        var p = start
        while (p < d.size) {
            val n = d[p].toInt() and 0xFF
            p += 1
            if (n == 0) return p
            p += n
        }
        return p
    }

    private fun fillRect(c: IntArray, w: Int, h: Int, left: Int, top: Int, iw: Int, ih: Int) {
        for (y in top until minOf(h, top + ih)) {
            val row = y * w
            for (x in left until minOf(w, left + iw)) {
                c[row + x] = 0
            }
        }
    }

    private fun drawImage(
        canvas: IntArray, cw: Int, ch: Int,
        pixels: IntArray, iw: Int, ih: Int,
        left: Int, top: Int, interlaced: Boolean,
        table: IntArray, transIndex: Int
    ) {
        // 计算行顺序
        val rows: IntArray = if (interlaced) {
            val r = IntArray(ih)
            var idx = 0
            for (start in intArrayOf(0, 4, 2, 1)) {
                val step = when (start) {
                    0 -> 8
                    4 -> 8
                    2 -> 4
                    else -> 2
                }
                var y = start
                while (y < ih && idx < ih) {
                    r[idx++] = y
                    y += step
                }
            }
            r
        } else {
            IntArray(ih) { it }
        }

        for (i in 0 until ih) {
            val y = top + rows[i]
            if (y < 0 || y >= ch) continue
            val rowBase = y * cw
            val srcBase = i * iw
            for (x in 0 until iw) {
                val idx = pixels[srcBase + x]
                if (idx == transIndex) continue
                if (idx < 0 || idx >= table.size) continue
                val tx = left + x
                if (tx < 0 || tx >= cw) continue
                canvas[rowBase + tx] = table[idx]
            }
        }
    }

    private fun downscale(src: IntArray, sw: Int, sh: Int, ow: Int, oh: Int, s: Int): IntArray {
        if (s == 1) return src.copyOf()
        val dst = IntArray(ow * oh)
        for (y in 0 until oh) {
            val sy = y * s
            if (sy >= sh) break
            val srcRow = sy * sw
            val dstRow = y * ow
            for (x in 0 until ow) {
                val sx = x * s
                if (sx >= sw) break
                dst[dstRow + x] = src[srcRow + sx]
            }
        }
        return dst
    }

    /** GIF LZW 解码，返回索引数组 */
    private fun lzwDecode(data: ByteArray, expected: Int, minCodeSizeIn: Int): IntArray? {
        if (data.isEmpty() || expected <= 0) return null
        val minCodeSize = minCodeSizeIn.coerceIn(2, 8)
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        val out = IntArray(expected)
        var outPos = 0

        val prefix = IntArray(4096)
        val suffix = IntArray(4096)
        val stack = IntArray(4097)

        var codeSize = minCodeSize + 1
        var next = eoi + 1
        var prev = -1
        var bitPos = 0
        var totalBits = data.size * 8

        fun readCode(size: Int): Int {
            if (bitPos + size > totalBits) return -1
            var result = 0
            var shift = 0
            var remaining = size
            var bytePos = bitPos shr 3
            var bitOffset = bitPos and 7
            while (remaining > 0) {
                if (bytePos >= data.size) return -1
                val b = data[bytePos].toInt() and 0xFF
                val available = 8 - bitOffset
                val take = minOf(available, remaining)
                val mask = (1 shl take) - 1
                result = result or (((b shr bitOffset) and mask) shl shift)
                shift += take
                remaining -= take
                bitOffset = 0
                bytePos++
            }
            bitPos += size
            return result
        }

        while (outPos < expected) {
            val code = readCode(codeSize)
            if (code < 0 || code == eoi) break
            if (code == clear) {
                codeSize = minCodeSize + 1
                next = eoi + 1
                prev = -1
                continue
            }
            var sp = 0
            if (prev == -1) {
                if (code >= clear) break
                out[outPos++] = code
                prev = code
                continue
            }
            if (code < next) {
                // 输出 code 对应字符串（逆序入栈）
                var c = code
                while (c >= clear) {
                    if (c >= next) break
                    stack[sp++] = suffix[c]
                    c = prefix[c]
                    if (sp >= stack.size) break
                }
                stack[sp++] = c
                // 新词条：prev + code 的首字符（栈顶即首字符）
                if (next < 4096) {
                    prefix[next] = prev
                    suffix[next] = stack[sp - 1]
                    next++
                }
            } else if (code == next) {
                // KwKwK 特例：prev + first(prev)
                var c = prev
                while (c >= clear) {
                    stack[sp++] = suffix[c]
                    c = prefix[c]
                    if (sp >= stack.size) break
                }
                stack[sp++] = c
                val first = stack[sp - 1]
                if (next < 4096) {
                    prefix[next] = prev
                    suffix[next] = first
                    next++
                }
                // 再补一个首字符到输出串末尾
                stack[sp++] = first
            } else {
                break // 损坏数据
            }
            // 弹出
            while (sp > 0 && outPos < expected) {
                out[outPos++] = stack[--sp]
            }
            prev = code
            // 码长增长：解码端与编码端同步在词条号达到 2^codeSize 时扩位
            if (next == (1 shl codeSize) && codeSize < 12) codeSize++
        }
        return out
    }
}
