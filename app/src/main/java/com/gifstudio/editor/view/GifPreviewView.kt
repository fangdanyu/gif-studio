package com.gifstudio.editor.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.gifstudio.editor.edit.ProjectRenderer
import com.gifstudio.editor.model.DoodleStroke
import com.gifstudio.editor.model.GifProject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * GIF 预览视图：等比例居中播放合成后的帧，并实时绘制涂鸦/文字/贴纸图层。
 *
 * 三种交互模式：
 *  - [MODE_LAYER]：拖动图层位置；选中图层右下角出现缩放手柄，拖动可改文字/贴纸大小
 *  - [MODE_CROP]：在画面上拖框选区，拖角可缩放，框内拖动可平移
 *  - [MODE_DOODLE]：手指涂抹即画，松开一笔落库
 */
class GifPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val MODE_LAYER = 0
        const val MODE_CROP = 1
        const val MODE_DOODLE = 2
    }

    var project: GifProject? = null
        set(value) {
            field = value
            selectedKind = -1
            selectedIndex = -1
            currentFrame = 0
            cropRect.set(0f, 0f, 1f, 1f)
            liveStroke = null
            invalidate()
            scheduleNext()
        }

    private var frames: List<Bitmap> = emptyList()
    private var delays: List<Int> = emptyList()

    var playing = true
        set(value) {
            field = value
            if (value) scheduleNext() else handler.removeCallbacks(tick)
        }

    var overlayEditable = true

    /** 当前帧序号（逻辑序） */
    var currentFrame = 0
        set(value) {
            field = value
            invalidate()
        }

    var onFrameChanged: ((Int) -> Unit)? = null
    var onOverlaySelected: ((Int, Int) -> Unit)? = null
    var onOverlayMoved: (() -> Unit)? = null
    var onDoodleCommitted: (() -> Unit)? = null
    var onCropRectChanged: (() -> Unit)? = null

    /** 选中图层：kind 0=文字 1=贴纸，index 为列表下标 */
    var selectedKind = -1
    var selectedIndex = -1

    var editMode = MODE_LAYER
        set(value) {
            field = value
            if (value == MODE_CROP) {
                selectedKind = -1
                selectedIndex = -1
                cropRect.set(0f, 0f, 1f, 1f)
                cropAspect = null
            }
            liveStroke = null
            invalidate()
        }

    var doodleColor: Int = 0xFFFF3B30.toInt()
    var doodleWidthRatio: Float = 0.02f

    /** 裁剪框（相对当前画面 0~1） */
    val cropRect = RectF(0f, 0f, 1f, 1f)

    /** 裁剪固定比例（已是归一化的 宽/高，null 表示自由裁剪） */
    var cropAspect: Float? = null
        private set

    private var liveStroke: DoodleStroke? = null

    private val handler = Handler(Looper.getMainLooper())
    private val imageRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF6B3D.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF6B3D.toInt()
        style = Paint.Style.FILL
    }
    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val cropBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private var dragging = false
    private var dragDx = 0f
    private var dragDy = 0f

    /** 缩放手柄 */
    private var resizing = false
    private var resizeStartDistance = 0f
    private var resizeStartRatio = 0f

    /** 双指捏合：缩放 + 旋转 */
    private var transforming = false
    private var tfStartDist = 0f
    private var tfStartAngle = 0f
    private var tfStartSize = 0f
    private var tfStartRotation = 0f
    private var tfStartCx = 0f
    private var tfStartCy = 0f
    private var tfStartMidX = 0f
    private var tfStartMidY = 0f

    /** 裁剪框拖动 */
    private var cropHandle = -1 // -1 无 0左上 1右上 2右下 3左下 4整体 5新建
    private var cropAnchorX = 0f
    private var cropAnchorY = 0f

    private val tick = object : Runnable {
        override fun run() {
            val p = project ?: return
            if (!playing || frames.isEmpty()) return
            currentFrame = (currentFrame + 1) % frames.size
            onFrameChanged?.invoke(currentFrame)
            val d = delays.getOrElse(currentFrame) { 100 }
            handler.postDelayed(this, d.toLong().coerceIn(20, 5000))
        }
    }

    fun setFrames(list: List<Bitmap>, delaysMs: List<Int>) {
        frames = list
        delays = delaysMs
        currentFrame = currentFrame.coerceIn(0, max(0, list.size - 1))
        invalidate()
        scheduleNext()
    }

    fun scheduleNext() {
        handler.removeCallbacks(tick)
        if (playing && frames.isNotEmpty()) {
            val d = delays.getOrElse(currentFrame) { 100 }
            handler.postDelayed(tick, d.toLong().coerceIn(20, 5000))
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleNext()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = project ?: return
        val frame = frames.getOrNull(currentFrame) ?: return

        val pad = 8f
        val availW = width - pad * 2
        val availH = height - pad * 2
        val scale = min(availW / frame.width, availH / frame.height)
        val w = frame.width * scale
        val h = frame.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        imageRect.set(left, top, left + w, top + h)

        // 棋盘格底（提示透明区域）
        drawChecker(canvas, imageRect)

        val path = Path().apply {
            addRoundRect(imageRect, 12f, 12f, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(frame, null, imageRect, paint)
        canvas.translate(imageRect.left, imageRect.top)
        ProjectRenderer.drawOverlays(canvas, p, imageRect.width(), imageRect.height())
        liveStroke?.let { ProjectRenderer.drawDoodles(canvas, wrapperOf(it), imageRect.width(), imageRect.height()) }
        canvas.restore()

        when (editMode) {
            MODE_CROP -> drawCropOverlay(canvas)
            MODE_DOODLE -> Unit
            else -> drawSelection(canvas, p)
        }
    }

    private fun wrapperOf(stroke: DoodleStroke): GifProject {
        val p = GifProject()
        p.doodles.add(stroke)
        return p
    }

    private fun drawSelection(canvas: Canvas, p: GifProject) {
        if (selectedKind < 0) return
        val c = centerOf(p, selectedKind, selectedIndex)
        val half = halfSizesPx(p, selectedKind, selectedIndex)
        val cx = imageRect.left + c.first * imageRect.width()
        val cy = imageRect.top + c.second * imageRect.height()
        val rot = rotationOf(p, selectedKind, selectedIndex)

        canvas.save()
        if (rot != 0f) canvas.rotate(rot, cx, cy)
        val r = RectF(cx - half.first, cy - half.second, cx + half.first, cy + half.second)
        r.inset(-2f, -2f)
        canvas.drawRoundRect(r, 8f, 8f, selectionPaint)

        // 右下角缩放手柄
        val hx = r.right
        val hy = r.bottom
        canvas.drawCircle(hx, hy, handleRadius(), handlePaint)
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawCircle(hx, hy, handleRadius() - 3f, inner)
        canvas.restore()
    }

    private fun handleRadius(): Float = 8f * resources.displayMetrics.density

    private fun rotated(x: Float, y: Float, cx: Float, cy: Float, deg: Float): Pair<Float, Float> {
        if (deg == 0f) return x to y
        val rad = Math.toRadians(deg.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()
        val dx = x - cx
        val dy = y - cy
        return (cx + dx * cos - dy * sin) to (cy + dx * sin + dy * cos)
    }

    private fun drawCropOverlay(canvas: Canvas) {
        val r = RectF(
            imageRect.left + cropRect.left * imageRect.width(),
            imageRect.top + cropRect.top * imageRect.height(),
            imageRect.left + cropRect.right * imageRect.width(),
            imageRect.top + cropRect.bottom * imageRect.height()
        )
        // 框外压暗
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, r.top, dimPaint)
        canvas.drawRect(imageRect.left, r.bottom, imageRect.right, imageRect.bottom, dimPaint)
        canvas.drawRect(imageRect.left, r.top, r.left, r.bottom, dimPaint)
        canvas.drawRect(r.right, r.top, imageRect.right, r.bottom, dimPaint)

        canvas.drawRect(r, cropBorderPaint)
        // 三分线
        for (i in 1..2) {
            val x = r.left + r.width() * i / 3f
            canvas.drawLine(x, r.top, x, r.bottom, gridPaint)
            val y = r.top + r.height() * i / 3f
            canvas.drawLine(r.left, y, r.right, y, gridPaint)
        }
        // 四角手柄（往框内收一点，避免贴到屏幕边缘）
        val p = cornerPoints(r)
        for (pos in p) {
            canvas.drawCircle(pos.first, pos.second, handleRadius(), handlePaint)
        }
    }

    /** 四角手柄位置：从真正的角沿对角线向内收 inset */
    private fun cornerPoints(r: RectF): List<Pair<Float, Float>> {
        val inset = handleInset()
        return listOf(
            (r.left + inset) to (r.top + inset),
            (r.right - inset) to (r.top + inset),
            (r.right - inset) to (r.bottom - inset),
            (r.left + inset) to (r.bottom - inset)
        )
    }

    private fun handleInset(): Float = handleRadius() * 1.6f

    private fun drawChecker(canvas: Canvas, r: RectF) {
        val bg = Paint().apply { color = 0xFF1B1E25.toInt() }
        canvas.drawRoundRect(r, 12f, 12f, bg)
        val cell = 14f
        val light = Paint().apply { color = 0xFF23262E.toInt() }
        val rows = (r.height() / cell).toInt()
        val cols = (r.width() / cell).toInt()
        for (y in 0..rows) {
            for (x in 0..cols) {
                if ((x + y) % 2 == 0) {
                    val l = r.left + x * cell
                    val t = r.top + y * cell
                    if (l < r.right && t < r.bottom) {
                        canvas.drawRect(l, t, min(l + cell, r.right), min(t + cell, r.bottom), light)
                    }
                }
            }
        }
    }

    /** 图层的像素半宽/半高（未旋转时的包围盒） */
    private fun halfSizesPx(p: GifProject, kind: Int, index: Int): Pair<Float, Float> {
        val h = imageRect.height()
        val w = imageRect.width()
        return when (kind) {
            0 -> {
                val t = p.texts.getOrNull(index) ?: return 1f to 1f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.textSize = t.sizeRatio * h
                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                ((paint.measureText(t.text) / 2f) + w * 0.01f) to (t.sizeRatio * h / 2f + h * 0.01f)
            }
            1 -> {
                val s = p.stickers.getOrNull(index) ?: return 1f to 1f
                (s.sizeRatio * h / 2f) to (s.sizeRatio * h / 2f)
            }
            else -> {
                val img = p.images.getOrNull(index) ?: return 1f to 1f
                val hh = img.sizeRatio * h / 2f
                (hh * img.aspect) to hh
            }
        }
    }

    /** 缩放手柄在屏幕上的位置（考虑旋转） */
    private fun selectionHandlePosition(p: GifProject): Pair<Float, Float>? {
        if (selectedKind < 0) return null
        val c = centerOf(p, selectedKind, selectedIndex)
        val half = halfSizesPx(p, selectedKind, selectedIndex)
        val cx = imageRect.left + c.first * imageRect.width()
        val cy = imageRect.top + c.second * imageRect.height()
        val rot = rotationOf(p, selectedKind, selectedIndex)
        return rotated(cx + half.first, cy + half.second, cx, cy, rot)
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        val p = project ?: return false
        if (!overlayEditable) return false

        when (editMode) {
            MODE_CROP -> return handleCropTouch(event)
            MODE_DOODLE -> return handleDoodleTouch(event)
        }

        // 双指：捏合缩放 + 旋转（需要先有选中图层）
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (selectedKind >= 0 && event.pointerCount >= 2) {
                    beginTransform(p, event)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (transforming) {
                    updateTransform(p, event)
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (transforming) return true
            }
        }

        val nx = if (imageRect.width() > 0) (event.x - imageRect.left) / imageRect.width() else 0f
        val ny = if (imageRect.height() > 0) (event.y - imageRect.top) / imageRect.height() else 0f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hp = selectionHandlePosition(p)
                if (hp != null && hypot(event.x - hp.first, event.y - hp.second) <= handleRadius() * 2.6f) {
                    resizing = true
                    val c = centerOf(p, selectedKind, selectedIndex)
                    resizeStartDistance = hypot(
                        event.x - (imageRect.left + c.first * imageRect.width()),
                        event.y - (imageRect.top + c.second * imageRect.height())
                    ).coerceAtLeast(1f)
                    resizeStartRatio = sizeOf(p, selectedKind, selectedIndex)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                val hit = findOverlay(p, nx, ny)
                if (hit != null) {
                    selectedKind = hit.first
                    selectedIndex = hit.second
                    dragging = true
                    val c = centerOf(p, hit.first, hit.second)
                    dragDx = nx - c.first
                    dragDy = ny - c.second
                    onOverlaySelected?.invoke(hit.first, hit.second)
                    invalidate()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                selectedKind = -1
                selectedIndex = -1
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizing && selectedKind >= 0) {
                    val c = centerOf(p, selectedKind, selectedIndex)
                    val d = hypot(
                        event.x - (imageRect.left + c.first * imageRect.width()),
                        event.y - (imageRect.top + c.second * imageRect.height())
                    )
                    setSize(p, selectedKind, selectedIndex, resizeStartRatio * (d / resizeStartDistance))
                    invalidate()
                    return true
                }
                if (dragging && selectedKind >= 0) {
                    moveOverlay(p, selectedKind, selectedIndex, nx - dragDx, ny - dragDy)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (transforming) {
                    endTransform()
                    return true
                }
                if (resizing) {
                    resizing = false
                    onOverlayMoved?.invoke()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (dragging) {
                    dragging = false
                    onOverlayMoved?.invoke()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }
        return true
    }

    // ---------------------------------------------------------------- 双指缩放 / 旋转

    private fun beginTransform(p: GifProject, event: MotionEvent) {
        val c = centerOf(p, selectedKind, selectedIndex)
        tfStartSize = sizeOf(p, selectedKind, selectedIndex)
        tfStartRotation = rotationOf(p, selectedKind, selectedIndex)
        tfStartCx = c.first
        tfStartCy = c.second
        tfStartDist = pointerDistance(event).coerceAtLeast(1f)
        tfStartAngle = pointerAngle(event)
        tfStartMidX = (event.getX(0) + event.getX(1)) / 2f
        tfStartMidY = (event.getY(0) + event.getY(1)) / 2f
        transforming = true
        dragging = false
        resizing = false
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun updateTransform(p: GifProject, event: MotionEvent) {
        if (event.pointerCount < 2) return
        val scale = (pointerDistance(event) / tfStartDist).coerceIn(0.15f, 6f)
        val rot = tfStartRotation + (pointerAngle(event) - tfStartAngle)
        val midX = (event.getX(0) + event.getX(1)) / 2f
        val midY = (event.getY(0) + event.getY(1)) / 2f
        val dnx = if (imageRect.width() > 0) (midX - tfStartMidX) / imageRect.width() else 0f
        val dny = if (imageRect.height() > 0) (midY - tfStartMidY) / imageRect.height() else 0f
        setSize(p, selectedKind, selectedIndex, tfStartSize * scale)
        setRotation(p, selectedKind, selectedIndex, rot)
        moveOverlay(p, selectedKind, selectedIndex, tfStartCx + dnx, tfStartCy + dny)
        invalidate()
    }

    private fun endTransform() {
        if (!transforming) return
        transforming = false
        onOverlayMoved?.invoke()
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun pointerDistance(event: MotionEvent): Float =
        hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))

    private fun pointerAngle(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }


    // ---------------------------------------------------------------- 裁剪交互

    private fun handleCropTouch(event: MotionEvent): Boolean {
        val nx = if (imageRect.width() > 0) (event.x - imageRect.left) / imageRect.width() else 0f
        val ny = if (imageRect.height() > 0) (event.y - imageRect.top) / imageRect.height() else 0f
        val r = RectF(
            imageRect.left + cropRect.left * imageRect.width(),
            imageRect.top + cropRect.top * imageRect.height(),
            imageRect.left + cropRect.right * imageRect.width(),
            imageRect.top + cropRect.bottom * imageRect.height()
        )
        val touchSlop = handleRadius() * 2.6f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val corners = cornerPoints(r)
                // 角落优先（手柄已内缩，命中区同比放大）
                for (i in corners.indices) {
                    val c = corners[i]
                    if (hypot(event.x - c.first, event.y - c.second) <= touchSlop * 1.9f) {
                        cropHandle = i
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                if (r.contains(event.x, event.y) && cropRect.width() < 0.92f && cropRect.height() < 0.92f) {
                    // 框内拖动 = 整体平移（框已接近满画面时改为重新框选，符合直觉）
                    cropHandle = 4
                    cropAnchorX = nx
                    cropAnchorY = ny
                } else {
                    cropHandle = 5
                    cropAnchorX = nx.coerceIn(0f, 1f)
                    cropAnchorY = ny.coerceIn(0f, 1f)
                    cropRect.set(cropAnchorX, cropAnchorY, cropAnchorX, cropAnchorY)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (cropHandle) {
                    0, 1, 2, 3 -> {
                        // 对角固定，另一角跟随手指，再按固定比例修正
                        val ax = if (cropHandle == 0 || cropHandle == 3) cropRect.right else cropRect.left
                        val ay = if (cropHandle == 0 || cropHandle == 1) cropRect.bottom else cropRect.top
                        val px = nx.coerceIn(0f, 1f)
                        val py = ny.coerceIn(0f, 1f)
                        setRectByCorner(ax, ay, px, py)
                    }
                    4 -> {
                        val dx = nx - cropAnchorX
                        val dy = ny - cropAnchorY
                        val w = cropRect.width()
                        val h = cropRect.height()
                        val nl = (cropRect.left + dx).coerceIn(0f, 1f - w)
                        val nt = (cropRect.top + dy).coerceIn(0f, 1f - h)
                        cropRect.set(nl, nt, nl + w, nt + h)
                        cropAnchorX = nx
                        cropAnchorY = ny
                    }
                    else -> {
                        // 新建：以按下点为锚，朝拖动方向展开
                        val px = nx.coerceIn(0f, 1f)
                        val py = ny.coerceIn(0f, 1f)
                        setRectByCorner(cropAnchorX, cropAnchorY, px, py)
                    }
                }
                onCropRectChanged?.invoke()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cropHandle = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    /**
     * 由「锚点 + 手指点」确定选框：自由模式按两点矩形；固定比例时按面积更大的方向补齐，
     * 并保证整框落在画面内、且不因比例约束缩到无效尺寸。
     */
    private fun setRectByCorner(ax: Float, ay: Float, px: Float, py: Float) {
        val aspect = cropAspect
        var l = min(ax, px)
        var t = min(ay, py)
        var rr = max(ax, px)
        var bb = max(ay, py)
        var w = (rr - l).coerceAtLeast(0.06f)
        var h = (bb - t).coerceAtLeast(0.06f)

        if (aspect == null) {
            // 若锚点在右下/左上，clamp 时保持锚点不动
            if (ax <= px) { if (l + w > 1f) { w = 1f - l } } else { if (l < 0f) { l = 0f; w = rr } }
            if (ay <= py) { if (t + h > 1f) { h = 1f - t } } else { if (t < 0f) { t = 0f; h = bb } }
            cropRect.set(l, t, l + w.coerceAtLeast(0.06f), t + h.coerceAtLeast(0.06f))
            cropRectSorted()
            return
        }

        // 固定比例：以宽为主导，高不足时改以高为主导
        if (w / aspect >= h) {
            h = w / aspect
        } else {
            w = h * aspect
        }
        // 限制在画面内（锚点方向决定伸缩）
        if (w > 1f) { w = 1f; h = w / aspect }
        if (h > 1f) { h = 1f; w = h * aspect }
        val left = if (ax <= px) ax else ax - w
        val top = if (ay <= py) ay else ay - h
        val nl = left.coerceIn(0f, 1f - w)
        val nt = top.coerceIn(0f, 1f - h)
        cropRect.set(nl, nt, nl + w, nt + h)
        cropRectSorted()
    }

    private fun cropRectSorted() {
        var l = cropRect.left.coerceIn(0f, 1f)
        var t = cropRect.top.coerceIn(0f, 1f)
        var r = cropRect.right.coerceIn(0f, 1f)
        var b = cropRect.bottom.coerceIn(0f, 1f)
        if (r - l < 0.06f) r = (l + 0.06f).coerceAtMost(1f)
        if (b - t < 0.06f) b = (t + 0.06f).coerceAtMost(1f)
        if (r > 1f) { r = 1f; l = (1f - 0.06f).coerceAtLeast(0f) }
        if (b > 1f) { b = 1f; t = (1f - 0.06f).coerceAtLeast(0f) }
        cropRect.set(l, t, r, b)
    }

    /** 设置固定裁剪比例（传入 宽:高 的实际比例，如 1f、4f/3f），null 为自由 */
    fun setCropAspect(ratioWH: Float?) {
        cropAspect = if (ratioWH == null) null else {
            val imgAspect = if (imageRect.height() > 0) imageRect.width() / imageRect.height() else 1f
            (ratioWH / imgAspect).coerceAtLeast(0.01f)
        }
        if (cropAspect != null) fitCropToAspect()
        onCropRectChanged?.invoke()
        invalidate()
    }

    /** 按当前比例在画面中央放一个尽量大的选框 */
    private fun fitCropToAspect() {
        val a = cropAspect ?: return
        var w = 1f
        var h = w / a
        if (h > 1f) { h = 1f; w = h * a }
        val l = (1f - w) / 2f
        val t = (1f - h) / 2f
        cropRect.set(l, t, l + w, t + h)
    }


    /** 把当前裁剪框叠加到项目已有的裁剪区域上（可反复精修） */
    fun applyCropToProject() {
        val p = project ?: return
        val cw = p.cropRight - p.cropLeft
        val ch = p.cropBottom - p.cropTop
        val nl = p.cropLeft + cropRect.left * cw
        val nt = p.cropTop + cropRect.top * ch
        val nr = p.cropLeft + cropRect.right * cw
        val nb = p.cropTop + cropRect.bottom * ch
        p.cropLeft = nl.coerceIn(0f, 1f)
        p.cropTop = nt.coerceIn(0f, 1f)
        p.cropRight = nr.coerceIn(0.05f, 1f)
        p.cropBottom = nb.coerceIn(0.05f, 1f)
        cropRect.set(0f, 0f, 1f, 1f)
        if (cropAspect != null) fitCropToAspect()
        invalidate()
    }

    fun resetCropBox() {
        cropRect.set(0f, 0f, 1f, 1f)
        if (cropAspect != null) fitCropToAspect()
        onCropRectChanged?.invoke()
        invalidate()
    }

    // ---------------------------------------------------------------- 涂鸦交互

    private fun handleDoodleTouch(event: MotionEvent): Boolean {
        val nx = if (imageRect.width() > 0) (event.x - imageRect.left) / imageRect.width() else -1f
        val ny = if (imageRect.height() > 0) (event.y - imageRect.top) / imageRect.height() else -1f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val s = DoodleStroke(color = doodleColor, widthRatio = doodleWidthRatio)
                s.points.add(nx.coerceIn(0f, 1f))
                s.points.add(ny.coerceIn(0f, 1f))
                liveStroke = s
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val s = liveStroke ?: return true
                s.points.add(nx.coerceIn(0f, 1f))
                s.points.add(ny.coerceIn(0f, 1f))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val s = liveStroke
                liveStroke = null
                if (s != null && s.points.size >= 2) {
                    project?.doodles?.add(s)
                    onDoodleCommitted?.invoke()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return true
    }

    fun undoLastDoodle(): Boolean {
        val p = project ?: return false
        val last = p.doodles.removeLastOrNull() ?: return false
        invalidate()
        onDoodleCommitted?.invoke()
        return true
    }

    // ---------------------------------------------------------------- 图层工具

    private fun centerOf(p: GifProject, kind: Int, index: Int): Pair<Float, Float> {
        return when (kind) {
            0 -> (p.texts.getOrNull(index)?.cx ?: 0.5f) to (p.texts.getOrNull(index)?.cy ?: 0.5f)
            1 -> (p.stickers.getOrNull(index)?.cx ?: 0.5f) to (p.stickers.getOrNull(index)?.cy ?: 0.5f)
            else -> (p.images.getOrNull(index)?.cx ?: 0.5f) to (p.images.getOrNull(index)?.cy ?: 0.5f)
        }
    }

    private fun sizeOf(p: GifProject, kind: Int, index: Int): Float = when (kind) {
        0 -> p.texts.getOrNull(index)?.sizeRatio ?: 0.1f
        1 -> p.stickers.getOrNull(index)?.sizeRatio ?: 0.2f
        else -> p.images.getOrNull(index)?.sizeRatio ?: 0.2f
    }

    private fun setSize(p: GifProject, kind: Int, index: Int, size: Float) {
        val v = size.coerceIn(0.04f, 0.95f)
        when (kind) {
            0 -> p.texts.getOrNull(index)?.sizeRatio = v
            1 -> p.stickers.getOrNull(index)?.sizeRatio = v
            else -> p.images.getOrNull(index)?.sizeRatio = v
        }
    }

    private fun rotationOf(p: GifProject, kind: Int, index: Int): Float = when (kind) {
        0 -> p.texts.getOrNull(index)?.rotation ?: 0f
        1 -> p.stickers.getOrNull(index)?.rotation ?: 0f
        else -> p.images.getOrNull(index)?.rotation ?: 0f
    }

    private fun setRotation(p: GifProject, kind: Int, index: Int, deg: Float) {
        val v = ((deg % 360f) + 360f) % 360f
        when (kind) {
            0 -> p.texts.getOrNull(index)?.rotation = v
            1 -> p.stickers.getOrNull(index)?.rotation = v
            else -> p.images.getOrNull(index)?.rotation = v
        }
    }

    private fun moveOverlay(p: GifProject, kind: Int, index: Int, cx: Float, cy: Float) {
        val x = cx.coerceIn(0.02f, 0.98f)
        val y = cy.coerceIn(0.02f, 0.98f)
        when (kind) {
            0 -> p.texts.getOrNull(index)?.let { it.cx = x; it.cy = y }
            1 -> p.stickers.getOrNull(index)?.let { it.cx = x; it.cy = y }
            else -> p.images.getOrNull(index)?.let { it.cx = x; it.cy = y }
        }
    }

    /** 命中测试：图片 → 表情贴纸 → 文字，自上而下；考虑旋转 */
    private fun findOverlay(p: GifProject, nx: Float, ny: Float): Pair<Int, Int>? {
        for (i in p.images.indices.reversed()) {
            if (hitLayer(p, 2, i, nx, ny)) return 2 to i
        }
        for (i in p.stickers.indices.reversed()) {
            if (hitLayer(p, 1, i, nx, ny)) return 1 to i
        }
        for (i in p.texts.indices.reversed()) {
            if (hitLayer(p, 0, i, nx, ny)) return 0 to i
        }
        return null
    }

    private fun hitLayer(p: GifProject, kind: Int, index: Int, nx: Float, ny: Float): Boolean {
        if (imageRect.width() <= 0 || imageRect.height() <= 0) return false
        val c = centerOf(p, kind, index)
        val half = halfSizesPx(p, kind, index)
        val cx = imageRect.left + c.first * imageRect.width()
        val cy = imageRect.top + c.second * imageRect.height()
        val px = nx * imageRect.width() + imageRect.left
        val py = ny * imageRect.height() + imageRect.top
        val rot = rotationOf(p, kind, index)
        // 反向旋转到图层本地坐标系
        val (lx, ly) = rotated(px, py, cx, cy, -rot)
        return abs(lx - cx) <= half.first + handleRadius() * 0.5f &&
            abs(ly - cy) <= half.second + handleRadius() * 0.5f
    }


    fun release() {
        handler.removeCallbacks(tick)
    }
}
