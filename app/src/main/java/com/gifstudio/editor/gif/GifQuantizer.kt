package com.gifstudio.editor.gif

/**
 * 颜色量化：中位切分（median cut）+ 15bit 颜色缓存查找。
 * 纯 JVM 实现，不依赖 android.graphics，方便单元测试。
 */
object GifQuantizer {

    class Quantized(
        /** 已补齐到 2 的幂的调色板（ARGB，透明位不使用） */
        val palette: IntArray,
        /** 调色板表大小（2 的幂，2..256），用于 LZW minCodeSize */
        val tableSize: Int,
        /** 实际颜色数 */
        val colorCount: Int,
        /** 每个像素在调色板中的索引 */
        val indices: IntArray,
        /** 透明色索引，-1 表示无透明 */
        val transparentIndex: Int
    )

    private const val CELLS = 32768 // 5bit*3

    fun quantize(pixels: IntArray, maxColors: Int = 256): Quantized {
        var hasAlpha = false
        for (p in pixels) {
            if ((p ushr 24) < 128) {
                hasAlpha = true
                break
            }
        }
        val targetColors = if (hasAlpha) maxColors - 1 else maxColors

        // 1. 统计 15bit 直方图
        val count = IntArray(CELLS)
        val sumR = IntArray(CELLS)
        val sumG = IntArray(CELLS)
        val sumB = IntArray(CELLS)
        var opaquePixels = 0
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val cell = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            count[cell]++
            sumR[cell] += r
            sumG[cell] += g
            sumB[cell] += b
            opaquePixels++
        }
        if (opaquePixels == 0) {
            // 全透明图：单色 + 透明
            val tableSize = 2
            return Quantized(
                IntArray(tableSize) { 0xFF000000.toInt() },
                tableSize, 1,
                IntArray(pixels.size) { 1 },
                1
            )
        }

        // 2. 收集有效单元
        var usedCells = 0
        for (i in 0 until CELLS) if (count[i] > 0) usedCells++
        val cellIdx = IntArray(usedCells)
        var k = 0
        for (i in 0 until CELLS) if (count[i] > 0) cellIdx[k++] = i

        // 单元平均色
        val cellColor = IntArray(usedCells)
        for (i in 0 until usedCells) {
            val c = cellIdx[i]
            val n = count[c]
            val r = sumR[c] / n
            val g = sumG[c] / n
            val b = sumB[c] / n
            cellColor[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val cellCount = IntArray(usedCells) { count[cellIdx[it]] }

        if (usedCells <= targetColors) {
            // 颜色不多，直接用
            return buildResult(cellColor, cellCount, usedCells, maxColors, hasAlpha, pixels)
        }

        // 3. 中位切分
        class Box(val from: Int, val to: Int) {
            var rMin = 255; var rMax = 0
            var gMin = 255; var gMax = 0
            var bMin = 255; var bMax = 0
            var total = 0

            fun measure(colors: IntArray, counts: IntArray) {
                rMin = 255; rMax = 0; gMin = 255; gMax = 0; bMin = 255; bMax = 0; total = 0
                for (i in from until to) {
                    val c = colors[i]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (r < rMin) rMin = r
                    if (r > rMax) rMax = r
                    if (g < gMin) gMin = g
                    if (g > gMax) gMax = g
                    if (b < bMin) bMin = b
                    if (b > bMax) bMax = b
                    total += counts[i]
                }
            }

            val maxRange: Int get() = maxOf(rMax - rMin, gMax - gMin, bMax - bMin)
            val splittable: Boolean get() = (to - from) > 1
        }

        val working = cellColor.copyOf()
        var workingCount = cellCount.copyOf()
        var boxes = mutableListOf(Box(0, usedCells).also { it.measure(working, workingCount) })

        while (boxes.size < targetColors) {
            var best = -1
            var bestScore = -1L
            for (i in boxes.indices) {
                val b = boxes[i]
                if (!b.splittable) continue
                val score = b.maxRange.toLong() * b.total
                if (score > bestScore) {
                    bestScore = score
                    best = i
                }
            }
            if (best < 0) break
            val box = boxes[best]
            // 选最长通道
            val channel = when (maxOf(box.rMax - box.rMin, box.gMax - box.gMin, box.bMax - box.bMin)) {
                box.rMax - box.rMin -> 2
                box.gMax - box.gMin -> 1
                else -> 0
            }
            // 排序
            val sub = IntArray(box.to - box.from) { box.from + it }
            val comparator = when (channel) {
                2 -> Comparator<Int> { a, b ->
                    val ca = working[a]; val cb = working[b]
                    ((ca shr 16) and 0xFF) - ((cb shr 16) and 0xFF)
                }
                1 -> Comparator<Int> { a, b ->
                    val ca = working[a]; val cb = working[b]
                    ((ca shr 8) and 0xFF) - ((cb shr 8) and 0xFF)
                }
                else -> Comparator<Int> { a, b ->
                    val ca = working[a]; val cb = working[b]
                    (ca and 0xFF) - (cb and 0xFF)
                }
            }
            val sorted = sub.sortedWith(comparator)
            // 仅重排该盒区间，其余区间保持原样（必须基于当前数组整体拷贝）
            val newColors = working.copyOf()
            val newCounts = workingCount.copyOf()
            for (i in box.from until box.to) {
                newColors[i] = working[sorted[i - box.from]]
                newCounts[i] = workingCount[sorted[i - box.from]]
            }
            System.arraycopy(newColors, 0, working, 0, working.size)
            System.arraycopy(newCounts, 0, workingCount, 0, workingCount.size)

            // 按像素数中位分割
            var acc = 0
            var splitAt = box.from + 1
            for (i in box.from until box.to) {
                acc += workingCount[i]
                if (acc * 2 >= box.total) {
                    splitAt = i + 1
                    break
                }
            }
            if (splitAt <= box.from) splitAt = box.from + 1
            if (splitAt >= box.to) splitAt = box.to - 1
            val b1 = Box(box.from, splitAt).also { it.measure(working, workingCount) }
            val b2 = Box(splitAt, box.to).also { it.measure(working, workingCount) }
            boxes.removeAt(best)
            boxes.add(b1)
            boxes.add(b2)
        }

        // 4. 每个 box 生成一个颜色
        val colors = IntArray(boxes.size)
        val countsForBox = IntArray(boxes.size)
        for ((bi, box) in boxes.withIndex()) {
            var sr = 0L; var sg = 0L; var sb = 0L; var n = 0L
            for (i in box.from until box.to) {
                val c = working[i]
                val w = workingCount[i].toLong()
                sr += ((c shr 16) and 0xFF).toLong() * w
                sg += ((c shr 8) and 0xFF).toLong() * w
                sb += (c and 0xFF).toLong() * w
                n += w
            }
            if (n == 0L) n = 1
            val r = (sr / n).toInt()
            val g = (sg / n).toInt()
            val b = (sb / n).toInt()
            colors[bi] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            countsForBox[bi] = n.toInt()
        }
        return buildResult(colors, countsForBox, boxes.size, maxColors, hasAlpha, pixels)
    }

    private fun buildResult(
        colors: IntArray,
        counts: IntArray,
        colorCountRaw: Int,
        maxColors: Int,
        hasAlpha: Boolean,
        pixels: IntArray? = null
    ): Quantized {
        val realCount = minOf(colorCountRaw, maxColors)
        var needed = realCount + (if (hasAlpha) 1 else 0)
        var tableSize = 2
        while (tableSize < needed) tableSize *= 2
        if (tableSize > 256) tableSize = 256
        val palette = IntArray(tableSize) { if (it < realCount) colors[it] else 0xFF000000.toInt() }
        val transparentIndex = if (hasAlpha) minOf(realCount, tableSize - 1) else -1

        val px = pixels ?: return Quantized(palette, tableSize, realCount, IntArray(0), transparentIndex)
        val indices = IntArray(px.size)
        val cache = IntArray(CELLS) { -1 }
        for (i in px.indices) {
            val p = px[i]
            if ((p ushr 24) < 128) {
                indices[i] = transparentIndex
                continue
            }
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val cell = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            var idx = cache[cell]
            if (idx < 0) {
                idx = nearest(palette, realCount, r, g, b)
                cache[cell] = idx
            }
            indices[i] = idx
        }
        return Quantized(palette, tableSize, realCount, indices, transparentIndex)
    }

    private fun nearest(palette: IntArray, size: Int, r: Int, g: Int, b: Int): Int {
        var best = 0
        var bestDist = Long.MAX_VALUE
        for (i in 0 until size) {
            val c = palette[i]
            val dr = ((c shr 16) and 0xFF) - r
            val dg = ((c shr 8) and 0xFF) - g
            val db = (c and 0xFF) - b
            val d = (dr * dr + dg * dg + db * db).toLong()
            if (d < bestDist) {
                bestDist = d
                best = i
                if (d == 0L) break
            }
        }
        return best
    }
}
