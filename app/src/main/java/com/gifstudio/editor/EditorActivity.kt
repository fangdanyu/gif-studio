package com.gifstudio.editor

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gifstudio.editor.edit.ProjectRenderer
import com.gifstudio.editor.media.ExportEngine
import com.gifstudio.editor.media.GifStore
import com.gifstudio.editor.media.MediaLoader
import com.gifstudio.editor.model.FilterPreset
import com.gifstudio.editor.model.GifProject
import com.gifstudio.editor.model.ImageOverlay
import com.gifstudio.editor.model.StickerOverlay
import com.gifstudio.editor.model.TextOverlay
import com.gifstudio.editor.ui.Ui
import com.gifstudio.editor.view.GifPreviewView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale
import kotlin.math.max

class EditorActivity : AppCompatActivity() {

    companion object {
        private const val PREVIEW_MAX_EDGE = 420
        private const val MAX_FRAMES_FOR_STRIP = 150
        /** 裁剪固定比例（宽:高） */
        private val cropRatios = listOf(
            "1:1" to 1f,
            "3:4" to 3f / 4f,
            "4:3" to 4f / 3f,
            "16:9" to 16f / 9f,
            "9:16" to 9f / 16f
        )
    }

    private lateinit var holder: GifProjectHolder
    private val project: GifProject get() = holder.project

    private lateinit var preview: GifPreviewView
    private lateinit var frameStrip: LinearLayout
    private lateinit var toolBar: LinearLayout
    private lateinit var tvInfo: TextView
    private lateinit var tvFrameBadge: TextView
    private lateinit var btnPlay: ImageButton

    private var previewFrames: MutableList<Bitmap> = mutableListOf()
    private var thumbs: MutableList<Bitmap> = mutableListOf()
    private var stripViews: MutableList<View> = mutableListOf()
    private var selectedFrame = 0
    private var pendingExport: ExportEngine.Quality? = null
    private var pendingTargetBytes = 0L
    private var pendingLoop = 0

    /** 帧多选删除模式 */
    private var selectingFrames = false
    private val multiSelected = linkedSetOf<Int>()

    /** 是否已成功导出过（用于退出前提醒） */
    private var exportedOnce = false
    /** 图层操作提示只弹一次 */
    private var overlayHintShown = false

    /** 导出方式点击后预估体积的缓存 */
    private val estimateBytes = HashMap<String, ByteArray>()
    private var estimateToken = 0

    /** 从相册添加自定义贴纸 */
    private val pickStickerImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val bmp = MediaLoader.loadBitmap(this, uri, 480)
        if (bmp == null) {
            Ui.toast(this, "图片读取失败")
            return@registerForActivityResult
        }
        val longest = max(bmp.width, bmp.height).toFloat()
        project.images.add(
            ImageOverlay(bitmap = bmp, cx = 0.5f, cy = 0.42f, sizeRatio = (300f / longest).coerceIn(0.12f, 0.6f))
        )
        preview.selectedKind = 2
        preview.selectedIndex = project.images.size - 1
        preview.invalidate()
        stickerDialog?.dismiss()
        rebuildTools()
        Ui.toast(this, "图片贴纸已添加，可双指缩放旋转")
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val q = pendingExport
        val target = pendingTargetBytes
        val loop = pendingLoop
        pendingExport = null
        pendingTargetBytes = 0
        if (q != null || target > 0) {
            if (target > 0) beginExportBySize(target, loop, granted)
            else if (q != null) beginExport(q, loop, granted)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val h = ProjectHolder.current
        if (h == null || h.project.frameCount == 0) {
            Ui.toast(this, "没有可编辑的内容")
            finish()
            return
        }
        holder = h
        setContentView(R.layout.activity_editor)
        Ui.applySystemBarInsets(this)

        preview = findViewById(R.id.preview)
        frameStrip = findViewById(R.id.frameStrip)
        toolBar = findViewById(R.id.toolBar)
        tvInfo = findViewById(R.id.tvInfo)
        tvFrameBadge = findViewById(R.id.tvFrameBadge)
        btnPlay = findViewById(R.id.btnPlay)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { confirmExit() }
        findViewById<TextView>(R.id.tvTitle).text = "编辑 · ${project.frameCount} 帧"
        findViewById<TextView>(R.id.btnExport).setOnClickListener { showExportDialog() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmExit()
        })

        preview.project = project
        preview.onFrameChanged = { i ->
            selectedFrame = i
            updateBadge()
            highlightStrip()
        }
        preview.onOverlaySelected = { kind, index ->
            val action = "可拖动移动、拖右下角圆点缩放、双指捏合缩放与旋转"
            val msg = when (kind) {
                0 -> "已选中文字「${project.texts.getOrNull(index)?.text ?: ""}」，$action"
                2 -> "已选中图片贴纸，$action"
                else -> "已选中贴纸，$action"
            }
            if (!overlayHintShown) {
                overlayHintShown = true
                Ui.toast(this, msg)
            }
        }
        preview.onDoodleCommitted = {
            rebuildStrip()
            updateInfo()
        }

        btnPlay.setOnClickListener {
            preview.playing = !preview.playing
            btnPlay.setImageResource(if (preview.playing) R.drawable.ic_pause else R.drawable.ic_play)
        }

        rebuildPreview()
        rebuildStrip()
        rebuildTools()
        updateInfo()
    }

    private fun updateBadge() {
        tvFrameBadge.text = "${selectedFrame + 1} / ${project.frameCount}"
    }

    /** 退出编辑器：还没导出时给一次「导出 / 放弃」的选择 */
    private fun confirmExit() {
        if (exportedOnce) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("还没有导出")
            .setMessage("当前编辑只在应用内，退出后不会保存到「我的作品」和相册。要现在导出吗？")
            .setPositiveButton("导出") { _, _ -> showExportDialog() }
            .setNegativeButton("放弃修改") { _, _ -> finish() }
            .setNeutralButton("继续编辑", null)
            .show()
    }

    // ---------------------------------------------------------------- 渲染

    private fun rebuildPreview() {
        val size = ProjectRenderer.outputSize(project, PREVIEW_MAX_EDGE)
        val delays = (0 until project.frameCount).map { project.delayOf(it) }
        val newFrames = ArrayList<Bitmap>(project.frameCount)
        for (i in 0 until project.frameCount) {
            newFrames.add(ProjectRenderer.renderBase(project, i, size[0], size[1]))
        }
        val old = previewFrames.toList()
        previewFrames = newFrames
        preview.setFrames(newFrames, delays)
        for (b in old) if (!b.isRecycled) b.recycle()
        updateBadge()
    }

    private fun refreshDelays() {
        val delays = (0 until project.frameCount).map { project.delayOf(it) }
        preview.setFrames(previewFrames, delays)
    }

    private fun rebuildStrip() {
        frameStrip.removeAllViews()
        for (b in thumbs) if (!b.isRecycled) b.recycle()
        thumbs.clear()
        stripViews.clear()

        val count = minOf(project.frameCount, MAX_FRAMES_FOR_STRIP)
        for (i in 0 until count) {
            val thumb = ProjectRenderer.renderThumb(project, i, 110)
            thumbs.add(thumb)
            val wrap = FrameLayout(this)
            val iv = ImageView(this)
            iv.setImageBitmap(thumb)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            wrap.addView(iv, FrameLayout.LayoutParams(
                Ui.dp(this, 52f), Ui.dp(this, 52f)
            ))
            wrap.setOnClickListener {
                if (selectingFrames) {
                    if (multiSelected.contains(i)) multiSelected.remove(i) else multiSelected.add(i)
                    highlightStrip()
                    rebuildTools()
                    return@setOnClickListener
                }
                selectedFrame = i
                preview.playing = false
                preview.currentFrame = i
                btnPlay.setImageResource(R.drawable.ic_play)
                updateBadge()
                highlightStrip()
            }
            wrap.setOnLongClickListener {
                if (!selectingFrames) {
                    selectingFrames = true
                    multiSelected.clear()
                    multiSelected.add(i)
                    highlightStrip()
                    rebuildTools()
                    Ui.toast(this, "已进入多选：点选要删除的帧")
                    true
                } else {
                    false
                }
            }
            val lp = LinearLayout.LayoutParams(Ui.dp(this, 52f), Ui.dp(this, 52f))
            lp.marginEnd = Ui.dp(this, 8f)
            frameStrip.addView(wrap, lp)
            stripViews.add(wrap)
        }
        highlightStrip()
    }

    private fun highlightStrip() {
        for ((i, v) in stripViews.withIndex()) {
            val selected = if (selectingFrames) multiSelected.contains(i) else i == selectedFrame
            v.background = if (selected) {
                Ui.rounded(0xFFFF6B3D.toInt(), 12f, this, 2, 0xFFFF8A3D.toInt())
            } else {
                Ui.rounded(0xFF2A2E37.toInt(), 12f, this, 0, 0)
            }
            val alpha = if (selectingFrames && !selected) 0.45f else 1f
            v.alpha = alpha
            val child = (v as? ViewGroup)?.getChildAt(0)
            child?.let {
                it.clipToOutline = true
                it.background = Ui.rounded(0xFF2A2E37.toInt(), 10f, this)
            }
        }
    }

    private fun deleteSelectedFrames() {
        if (multiSelected.isEmpty()) {
            Ui.toast(this, "请先点选要删除的帧")
            return
        }
        val maxDeletable = project.frameCount - 1
        if (maxDeletable <= 0) {
            Ui.toast(this, "至少要保留 1 帧，无法再删除")
            return
        }
        val wanted = multiSelected.size
        project.removeFrames(multiSelected.toList())
        val deleted = wanted.coerceAtMost(maxDeletable)
        selectingFrames = false
        multiSelected.clear()
        selectedFrame = selectedFrame.coerceIn(0, max(0, project.frameCount - 1))
        preview.playing = false
        preview.currentFrame = selectedFrame
        btnPlay.setImageResource(R.drawable.ic_play)
        findViewById<TextView>(R.id.tvTitle).text = "编辑 · ${project.frameCount} 帧"
        afterVisualChange()
        rebuildTools()
        Ui.toast(this, if (deleted == wanted) "已删除 $deleted 帧" else "已删除 $deleted 帧（至少保留 1 帧）")
    }

    private fun afterVisualChange(rebuildThumbs: Boolean = true) {
        estimateBytes.clear()
        rebuildPreview()
        if (rebuildThumbs) rebuildStrip()
        rebuildTools()
        updateInfo()
        preview.invalidate()
    }

    private fun updateInfo() {
        val size = ProjectRenderer.outputSize(project, null)
        val durationMs = (0 until project.frameCount).sumOf { project.delayOf(it) }
        tvInfo.text = String.format(
            Locale.US,
            "输出 %d×%d · %d 帧 · 时长 %.1fs · 循环 %s",
            size[0], size[1], project.frameCount, durationMs / 1000f,
            if (project.loopCount <= 0) "无限" else "${project.loopCount} 次"
        )
    }

    // ---------------------------------------------------------------- 工具栏

    private fun rebuildTools() {
        toolBar.removeAllViews()

        // 裁剪模式：拖框裁剪（含固定比例）
        if (preview.editMode == GifPreviewView.MODE_CROP) {
            addTool("应用裁剪", true) {
                preview.applyCropToProject()
                preview.editMode = GifPreviewView.MODE_LAYER
                selectedFrame = 0
                afterVisualChange()
            }
            addTool("重置选框") { preview.resetCropBox() }
            addTool("取消") {
                preview.editMode = GifPreviewView.MODE_LAYER
                preview.invalidate()
            }
            val freeLabel = "自由"
            addTool(freeLabel, cropRatioWH == null) {
                cropRatioWH = null
                preview.setCropAspect(null)
            }
            for ((label, ratio) in cropRatios) {
                addTool(label, cropRatioWH == ratio) {
                    cropRatioWH = ratio
                    preview.setCropAspect(ratio)
                }
            }
            return
        }

        // 涂鸦模式
        if (preview.editMode == GifPreviewView.MODE_DOODLE) {
            addTool("结束涂鸦", true) {
                preview.editMode = GifPreviewView.MODE_LAYER
                rebuildStrip()
                updateInfo()
            }
            addTool("撤销一笔") {
                if (!preview.undoLastDoodle()) Ui.toast(this, "没有可撤销的笔迹")
            }
            addTool("清空涂鸦", project.doodles.isNotEmpty()) {
                project.doodles.clear()
                preview.invalidate()
                rebuildStrip()
                updateInfo()
            }
            return
        }

        // 帧多选
        if (selectingFrames) {
            addTool("删除 ${multiSelected.size} 帧", multiSelected.isNotEmpty()) { deleteSelectedFrames() }
            addTool("全选") {
                for (i in 0 until minOf(project.frameCount, MAX_FRAMES_FOR_STRIP)) multiSelected.add(i)
                highlightStrip()
            }
            addTool("取消多选") {
                selectingFrames = false
                multiSelected.clear()
                highlightStrip()
            }
            return
        }

        addTool("裁剪") {
            preview.playing = false
            btnPlay.setImageResource(R.drawable.ic_play)
            preview.editMode = GifPreviewView.MODE_CROP
            Ui.toast(this, "在画面上拖出要保留的区域，拖角可调整，点「应用裁剪」生效")
        }
        addTool("尺寸") { showSizeDialog() }
        addTool(String.format(Locale.US, "速度 %.1fx", project.speed), project.speed != 1f) { showSpeedDialog() }
        addTool(if (project.reversed) "倒放 开" else "倒放 关", project.reversed) {
            project.reversed = !project.reversed
            selectedFrame = 0
            afterVisualChange()
        }
        addTool("滤镜 ${project.filter.label}", project.filter != FilterPreset.NONE) { showFilterDialog() }
        addTool("涂鸦") { showDoodleDialog() }
        addTool("文字") { showTextDialog() }
        addTool("贴纸") { showStickerDialog() }
        addTool("帧时长") { showDelayDialog() }
        addTool("选帧") {
            selectingFrames = true
            multiSelected.clear()
            preview.playing = false
            btnPlay.setImageResource(R.drawable.ic_play)
            highlightStrip()
        }
        addTool("清空图层", project.hasOverlay()) {
            project.clearOverlays()
            preview.selectedKind = -1
            preview.invalidate()
            afterVisualChange(false)
        }
    }

    /** 当前裁剪固定比例（宽:高），null 为自由 */
    private var cropRatioWH: Float? = null

    private fun addTool(label: String, active: Boolean = false, onClick: () -> Unit) {
        val chip = Ui.chip(this, label, active)
        chip.setTextColor(if (active) Color.WHITE else 0xFFD6DAE1.toInt())
        if (!active) chip.background = Ui.rounded(0xFF23262E.toInt(), 18f, this)
        chip.setOnClickListener {
            onClick()
            rebuildTools()
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = Ui.dp(this, 8f)
        toolBar.addView(chip, lp)
    }

    // ---------------------------------------------------------------- 涂鸦

    private fun showDoodleDialog() {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 8f), Ui.dp(ctx, 20f), Ui.dp(ctx, 8f))
        }
        root.addView(Ui.text(ctx, "画笔颜色", 13f, 0xFF8A8F99.toInt()))
        val colors = intArrayOf(
            0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFD400.toInt(), 0xFF35D07A.toInt(),
            0xFF3D7BFF.toInt(), 0xFFB15BFF.toInt(), Color.WHITE, 0xFF111111.toInt()
        )
        var chosenColor = colors[0]
        val colorRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dots = mutableListOf<View>()
        fun refreshDots() {
            for ((i, d) in dots.withIndex()) {
                (d.background as? GradientDrawable)?.setStroke(
                    Ui.dp(ctx, if (colors[i] == chosenColor) 3f else 1f),
                    if (colors[i] == chosenColor) 0xFFFF6B3D.toInt() else 0xFFDDDDDD.toInt()
                )
            }
        }
        for (c in colors) {
            val dot = View(ctx)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(Ui.dp(ctx, 1f), 0xFFDDDDDD.toInt())
            }
            dot.isClickable = true
            val lp = LinearLayout.LayoutParams(Ui.dp(ctx, 26f), Ui.dp(ctx, 26f))
            lp.marginEnd = Ui.dp(ctx, 8f)
            colorRow.addView(dot, lp)
            dots.add(dot)
            dot.setOnClickListener {
                chosenColor = c
                refreshDots()
            }
        }
        refreshDots()
        root.addView(colorRow)

        root.addView(Ui.text(ctx, "画笔粗细", 13f, 0xFF8A8F99.toInt()).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(ctx, 14f)
            layoutParams = lp
        })
        val widthLabel = Ui.text(ctx, "", 13f, 0xFFFF6B3D.toInt(), true)
        val seek = SeekBar(ctx).apply {
            max = 40
            progress = (preview.doodleWidthRatio / 0.06f * 40).toInt().coerceIn(0, 40)
        }
        fun label() {
            val ratio = 0.004f + seek.progress / 40f * 0.056f
            widthLabel.text = String.format(Locale.US, "粗细 %.0f%% 画高", ratio * 100)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = label()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        label()
        root.addView(seek)
        root.addView(widthLabel)
        root.addView(Ui.text(ctx, "进入后按住画面拖动即可涂鸦，涂鸦会应用到所有帧。", 12f, 0xFF8A8F99.toInt()).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(ctx, 10f)
            layoutParams = lp
        })

        MaterialAlertDialogBuilder(ctx)
            .setTitle("涂鸦")
            .setView(root)
            .setNegativeButton("取消", null)
            .setNeutralButton("清空已有涂鸦") { _, _ ->
                project.doodles.clear()
                preview.invalidate()
                rebuildStrip()
                updateInfo()
            }
            .setPositiveButton("开始涂鸦") { _, _ ->
                preview.doodleColor = chosenColor
                preview.doodleWidthRatio = 0.004f + seek.progress / 40f * 0.056f
                preview.playing = false
                btnPlay.setImageResource(R.drawable.ic_play)
                preview.editMode = GifPreviewView.MODE_DOODLE
                rebuildTools()
            }
            .show()
    }

    // ---------------------------------------------------------------- 尺寸

    private fun showSizeDialog() {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 8f), Ui.dp(ctx, 20f), Ui.dp(ctx, 8f))
        }
        val current = ProjectRenderer.outputSize(project, null)
        root.addView(Ui.text(ctx, "当前输出：${current[0]}×${current[1]}", 13f, 0xFF8A8F99.toInt()))
        root.addView(Ui.text(ctx, "宽度（高度按比例自动）", 13f, 0xFF8A8F99.toInt()).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(ctx, 14f)
            layoutParams = lp
        })
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (project.outWidth > 0) project.outWidth.toString() else current[0].toString())
            hint = "留空或 0 表示自适应"
        }
        root.addView(input)

        val presets = listOf(1280, 720, 640, 480, 320, 0)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in presets) {
            val chip = Ui.text(ctx, if (v == 0) "自适应" else "${v}px", 12.5f, 0xFF3A3F47.toInt()).apply {
                background = Ui.rounded(0xFFF1F3F7.toInt(), 16f, ctx)
                setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 7f), Ui.dp(ctx, 12f), Ui.dp(ctx, 7f))
                isClickable = true
                setOnClickListener { input.setText(if (v == 0) "0" else v.toString()) }
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.marginEnd = Ui.dp(ctx, 6f)
            row.addView(chip, lp)
        }
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(ctx, 12f) })

        MaterialAlertDialogBuilder(ctx)
            .setTitle("调整尺寸")
            .setView(root)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                val w = input.text.toString().toIntOrNull() ?: 0
                project.outWidth = w.coerceIn(0, 2000)
                project.outHeight = 0
                afterVisualChange()
            }
            .show()
    }

    // ---------------------------------------------------------------- 速度

    private fun showSpeedDialog() {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 16f), Ui.dp(ctx, 20f), Ui.dp(ctx, 8f))
        }
        val valueText = Ui.text(ctx, String.format(Locale.US, "%.1fx", project.speed), 16f, 0xFFFF6B3D.toInt(), true)
        valueText.gravity = Gravity.CENTER
        root.addView(valueText)
        val seek = SeekBar(ctx).apply {
            max = 25
            progress = ((project.speed - 0.5f) * 10).toInt().coerceIn(0, 25)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = 0.5f + progress / 10f
                valueText.text = String.format(Locale.US, "%.1fx", v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(seek)
        root.addView(Ui.text(ctx, "0.5x 慢动作 · 3.0x 加速播放", 12f, 0xFF8A8F99.toInt()).apply {
            gravity = Gravity.CENTER
        })

        MaterialAlertDialogBuilder(ctx)
            .setTitle("播放速度")
            .setView(root)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                project.speed = 0.5f + seek.progress / 10f
                refreshDelays()
                rebuildTools()
                updateInfo()
            }
            .show()
    }

    // ---------------------------------------------------------------- 滤镜

    private fun showFilterDialog() {
        val ctx = this
        val items = FilterPreset.values().map { it.label }
        val currentIndex = FilterPreset.values().indexOf(project.filter)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("滤镜")
            .setSingleChoiceItems(items.toTypedArray(), currentIndex) { d, which ->
                project.filter = FilterPreset.values()[which]
                afterVisualChange()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- 文字

    private fun showTextDialog() {
        val ctx = this
        val editing = preview.selectedKind == 0
        val current = if (editing) project.texts.getOrNull(preview.selectedIndex) else null

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 8f), Ui.dp(ctx, 20f), Ui.dp(ctx, 8f))
        }
        val input = EditText(ctx).apply {
            hint = "输入要添加的文字"
            setText(current?.text ?: "")
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(false)
            maxLines = 3
        }
        root.addView(input)

        root.addView(Ui.text(ctx, "字号", 13f, 0xFF8A8F99.toInt()).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(ctx, 14f)
            layoutParams = lp
        })
        val seek = SeekBar(ctx).apply {
            max = 22
            progress = (((current?.sizeRatio ?: 0.10f) * 100) - 8).toInt().coerceIn(0, 22)
        }
        root.addView(seek)

        root.addView(Ui.text(ctx, "颜色", 13f, 0xFF8A8F99.toInt()).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(ctx, 8f)
            layoutParams = lp
        })
        val colors = intArrayOf(
            Color.WHITE, 0xFF111111.toInt(), 0xFFFF6B3D.toInt(), 0xFFFFD400.toInt(),
            0xFF35D07A.toInt(), 0xFF3D7BFF.toInt(), 0xFFFF3B6B.toInt(), 0xFFB15BFF.toInt()
        )
        var chosenColor = current?.color ?: Color.WHITE
        val colorRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val colorDots = mutableListOf<View>()
        for (c in colors) {
            val dot = View(ctx)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(Ui.dp(ctx, 1f), 0xFFDDDDDD.toInt())
            }
            dot.isClickable = true
            val lp = LinearLayout.LayoutParams(Ui.dp(ctx, 26f), Ui.dp(ctx, 26f))
            lp.marginEnd = Ui.dp(ctx, 8f)
            colorRow.addView(dot, lp)
            colorDots.add(dot)
            dot.setOnClickListener {
                chosenColor = c
                for ((i, d) in colorDots.withIndex()) {
                    (d.background as GradientDrawable).setStroke(
                        Ui.dp(ctx, if (colors[i] == c) 3f else 1f),
                        if (colors[i] == c) 0xFFFF6B3D.toInt() else 0xFFDDDDDD.toInt()
                    )
                }
            }
        }
        root.addView(colorRow)

        root.addView(Ui.text(ctx, "样式", 13f, 0xFF8A8F99.toInt()).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(ctx, 12f)
            layoutParams = lp
        })
        var style = current?.style ?: 0
        val styleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val styles = listOf("描边", "纯色", "底色块")
        val styleChips = mutableListOf<TextView>()
        fun refreshStyle() {
            for ((i, c) in styleChips.withIndex()) {
                val sel = i == style
                c.background = if (sel) Ui.gradient(0xFFFF8A3D.toInt(), 0xFFFF4E6B.toInt(), 16f, ctx)
                else Ui.rounded(0xFFF1F3F7.toInt(), 16f, ctx)
                c.setTextColor(if (sel) Color.WHITE else 0xFF3A3F47.toInt())
            }
        }
        for ((i, s) in styles.withIndex()) {
            val chip = Ui.text(ctx, s, 13f, 0xFF3A3F47.toInt()).apply {
                setPadding(Ui.dp(ctx, 14f), Ui.dp(ctx, 7f), Ui.dp(ctx, 14f), Ui.dp(ctx, 7f))
                isClickable = true
                setOnClickListener { style = i; refreshStyle() }
            }
            styleChips.add(chip)
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.marginEnd = Ui.dp(ctx, 6f)
            styleRow.addView(chip, lp)
        }
        refreshStyle()
        root.addView(styleRow)

        root.addView(Ui.text(ctx, "提示：添加后可直接在画面上拖动文字位置", 12f, 0xFF8A8F99.toInt()).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(ctx, 12f)
            layoutParams = lp
        })

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (editing) "编辑文字" else "添加文字")
            .setView(ScrollView(ctx).apply { addView(root) })
            .setNegativeButton("取消", null)
            .setNeutralButton("删除") { _, _ ->
                if (editing) {
                    project.texts.removeAt(preview.selectedIndex)
                    preview.selectedKind = -1
                    preview.invalidate()
                    afterVisualChange(false)
                }
            }
            .setPositiveButton("确定") { _, _ ->
                val txt = input.text.toString()
                if (txt.isBlank()) return@setPositiveButton
                val sizeRatio = (8 + seek.progress) / 100f
                if (editing && current != null) {
                    current.text = txt
                    current.color = chosenColor
                    current.sizeRatio = sizeRatio
                    current.style = style
                } else {
                    project.texts.add(
                        TextOverlay(
                            text = txt,
                            cx = 0.5f,
                            cy = 0.8f,
                            sizeRatio = sizeRatio,
                            color = chosenColor,
                            style = style
                        )
                    )
                    preview.selectedKind = 0
                    preview.selectedIndex = project.texts.size - 1
                }
                preview.invalidate()
                afterVisualChange(false)
            }
            .show()
    }

    // ---------------------------------------------------------------- 贴纸

    private fun showStickerDialog() {
        val ctx = this
        val emojis = listOf(
            "😂", "😍", "🥳", "😎", "🤩", "😭", "😡", "🤔",
            "❤️", "🔥", "⭐", "💯", "👑", "🎉", "👍", "💩",
            "🌈", "🦄", "✨", "🍺", "🐶", "🐱", "🌸", "💣"
        )
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 16f), Ui.dp(ctx, 8f), Ui.dp(ctx, 16f), Ui.dp(ctx, 8f))
        }
        root.addView(Ui.text(ctx, "我的图片贴纸（从相册添加）", 12f, 0xFF8A8F99.toInt()))
        val importChip = Ui.text(ctx, "＋ 从相册添加图片", 13f, Color.WHITE, true).apply {
            background = Ui.gradient(0xFFFF8A3D.toInt(), 0xFFFF4E6B.toInt(), 16f, ctx)
            gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 14f), Ui.dp(ctx, 9f), Ui.dp(ctx, 14f), Ui.dp(ctx, 9f))
            isClickable = true
            setOnClickListener {
                stickerDialog?.dismiss()
                pickStickerImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        }
        root.addView(importChip, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = Ui.dp(ctx, 8f)
            bottomMargin = Ui.dp(ctx, 12f)
        })
        root.addView(Ui.text(ctx, "点选贴纸，添加后可拖动位置", 12f, 0xFF8A8F99.toInt()))
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        var row: LinearLayout? = null
        for ((i, e) in emojis.withIndex()) {
            if (i % 6 == 0) {
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row)
            }
            val tv = Ui.text(ctx, e, 26f, Color.BLACK).apply {
                gravity = Gravity.CENTER
                isClickable = true
            }
            val lp = LinearLayout.LayoutParams(Ui.dp(ctx, 48f), Ui.dp(ctx, 48f))
            lp.marginEnd = Ui.dp(ctx, 4f)
            row?.addView(tv, lp)
            tv.setOnClickListener { addSticker(e) }
        }
        root.addView(grid)
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("贴纸")
            .setView(root)
            .setNegativeButton("关闭", null)
            .create()
        stickerDialog = dialog
        dialog.show()
    }

    private var stickerDialog: android.app.Dialog? = null

    private fun addSticker(emoji: String) {
        project.stickers.add(StickerOverlay(emoji = emoji, cx = 0.5f, cy = 0.28f, sizeRatio = 0.2f))
        preview.selectedKind = 1
        preview.selectedIndex = project.stickers.size - 1
        preview.invalidate()
        rebuildTools()
        stickerDialog?.dismiss()
        Ui.toast(this, "贴纸已添加，可在画面上拖动")
    }

    // ---------------------------------------------------------------- 帧时长

    private fun showDelayDialog() {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 16f), Ui.dp(ctx, 20f), Ui.dp(ctx, 8f))
        }
        val value = Ui.text(ctx, "", 16f, 0xFFFF6B3D.toInt(), true).apply { gravity = Gravity.CENTER }
        val current = project.sourceDelays.firstOrNull() ?: 100
        val seek = SeekBar(ctx).apply {
            max = 95
            progress = ((current - 50) / 10).coerceIn(0, 95)
        }
        fun label() {
            value.text = "${50 + seek.progress * 10} ms"
        }
        label()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = label()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(value)
        root.addView(seek)
        root.addView(Ui.text(ctx, "统一设置每一帧的停留时间", 12f, 0xFF8A8F99.toInt()).apply {
            gravity = Gravity.CENTER
        })
        MaterialAlertDialogBuilder(ctx)
            .setTitle("帧时长")
            .setView(root)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                project.setUniformDelay(50 + seek.progress * 10)
                refreshDelays()
                updateInfo()
            }
            .show()
    }

    // ---------------------------------------------------------------- 导出

    private fun showExportDialog() {
        val ctx = this
        val qualities = ExportEngine.Quality.values()
        val modeLabels = listOf("高清", "标准", "压缩", "按大小")
        var modeIndex = 0
        var targetMb = 2.0f
        var loop = project.loopCount.coerceIn(0, 5)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 6f), Ui.dp(ctx, 20f), Ui.dp(ctx, 6f))
        }

        root.addView(Ui.text(ctx, "导出方式", 12.5f, 0xFF8A8F99.toInt()))
        val modeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val modeChips = mutableListOf<TextView>()
        val sizeBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val sizeLabel = Ui.text(ctx, "目标体积 2.0 MB", 13.5f, 0xFFFF6B3D.toInt(), true)
        val sizeSeek = SeekBar(ctx).apply {
            max = 95 // 0.5 ~ 10.0 MB，每格 0.1MB
            progress = ((targetMb - 0.5f) * 10f).toInt().coerceIn(0, 95)
        }
        root.addView(modeRow)
        val sizeHint = Ui.text(ctx, "", 12f, 0xFFFF6B3D.toInt()).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(ctx, 8f)
            layoutParams = lp
        }
        root.addView(sizeHint)

        /** 选中画质后预估（真的编码一遍，结果缓存给正式导出用） */
        fun refreshEstimate() {
            if (modeIndex == 3) {
                sizeHint.text = "按目标体积生成，实际大小会贴近滑杆值"
                return
            }
            val q = qualities[modeIndex]
            val key = "${q.name}:$loop"
            val cached = estimateBytes[key]
            if (cached != null) {
                sizeHint.text = "「${q.label}」档约 ${formatSize(cached.size.toLong())}"
                return
            }
            sizeHint.text = "正在计算「${q.label}」档体积…"
            val token = ++estimateToken
            Thread {
                val bytes = try {
                    ExportEngine.export(project, q, loop)
                } catch (e: Exception) {
                    null
                }
                runOnUiThread {
                    if (token != estimateToken || isFinishing) return@runOnUiThread
                    if (bytes == null) {
                        sizeHint.text = "体积计算失败，仍可直接导出"
                    } else {
                        estimateBytes[key] = bytes
                        sizeHint.text = "「${q.label}」档约 ${formatSize(bytes.size.toLong())}"
                    }
                }
            }.start()
        }

        fun refreshModes() {
            for ((i, c) in modeChips.withIndex()) {
                val sel = i == modeIndex
                c.background = if (sel) Ui.gradient(0xFFFF8A3D.toInt(), 0xFFFF4E6B.toInt(), 18f, ctx)
                else Ui.rounded(0xFFF1F3F7.toInt(), 18f, ctx)
                c.setTextColor(if (sel) Color.WHITE else 0xFF3A3F47.toInt())
                c.setTypeface(null, if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
            if (modeIndex == 3) {
                sizeBox.visibility = View.VISIBLE
            } else {
                sizeBox.visibility = View.GONE
            }
        }
        for ((i, label) in modeLabels.withIndex()) {
            val chip = Ui.text(ctx, label, 13f, 0xFF3A3F47.toInt()).apply {
                gravity = Gravity.CENTER
                isSingleLine = true
                setPadding(Ui.dp(ctx, 6f), Ui.dp(ctx, 7f), Ui.dp(ctx, 6f), Ui.dp(ctx, 7f))
                isClickable = true
                setOnClickListener {
                    modeIndex = i
                    refreshModes()
                    refreshEstimate()
                }
            }
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.marginEnd = Ui.dp(ctx, 5f)
            modeRow.addView(chip, lp)
            modeChips.add(chip)
        }
        refreshModes()
        refreshEstimate()

        sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                targetMb = 0.5f + progress / 10f
                sizeLabel.text = String.format(Locale.US, "目标体积 %.1f MB", targetMb)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        sizeBox.addView(Ui.space(ctx, 8f))
        sizeBox.addView(sizeLabel)
        sizeBox.addView(sizeSeek)
        sizeBox.addView(Ui.text(ctx, "体积越大画质越好；系统会自动挑最合适的尺寸与色彩。", 11.5f, 0xFF8A8F99.toInt()))
        root.addView(sizeBox)

        root.addView(Ui.text(ctx, "循环次数", 12.5f, 0xFF8A8F99.toInt()).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(ctx, 14f)
            layoutParams = lp
        })
        val loopLabels = listOf("无限", "1 次", "2 次", "3 次", "4 次", "5 次")
        val loopValues = listOf(0, 1, 2, 3, 4, 5)
        val loopRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val loopChips = mutableListOf<TextView>()
        fun refreshLoop() {
            for ((i, c) in loopChips.withIndex()) {
                val sel = loopValues[i] == loop
                c.background = if (sel) Ui.gradient(0xFFFF8A3D.toInt(), 0xFFFF4E6B.toInt(), 18f, ctx)
                else Ui.rounded(0xFFF1F3F7.toInt(), 18f, ctx)
                c.setTextColor(if (sel) Color.WHITE else 0xFF3A3F47.toInt())
                c.setTypeface(null, if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
        for ((i, label) in loopLabels.withIndex()) {
            val chip = Ui.text(ctx, label, 12f, 0xFF3A3F47.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(Ui.dp(ctx, 6f), Ui.dp(ctx, 7f), Ui.dp(ctx, 6f), Ui.dp(ctx, 7f))
                isClickable = true
                setOnClickListener {
                    loop = loopValues[i]
                    refreshLoop()
                    refreshEstimate()
                }
            }
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.marginEnd = Ui.dp(ctx, 4f)
            loopRow.addView(chip, lp)
            loopChips.add(chip)
        }
        refreshLoop()
        root.addView(loopRow)
        root.addView(Ui.text(ctx, "第 2 行起为按次数循环，播完指定次数后停在最后一帧。", 11.5f, 0xFF8A8F99.toInt()).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(ctx, 6f)
            layoutParams = lp
        })

        fun launchExport() {
            project.loopCount = loop
            if (modeIndex == 3) {
                val bytes = (targetMb * 1024f * 1024f).toLong()
                requestExport(null, bytes, loop)
            } else {
                requestExport(qualities[modeIndex], 0L, loop)
            }
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("导出 GIF")
            .setView(ScrollView(ctx).apply { addView(root) })
            .setNegativeButton("取消", null)
            .setPositiveButton("导出") { _, _ -> launchExport() }
            .show()
    }

    private fun formatSize(bytes: Long): String = if (bytes < 1024L * 1024L) {
        String.format(Locale.US, "%.0f KB", bytes / 1024f)
    } else {
        String.format(Locale.US, "%.2f MB", bytes / 1024f / 1024f)
    }

    /** 需要存储权限时先申请，记住本次导出参数 */
    private fun requestExport(q: ExportEngine.Quality?, targetBytes: Long, loop: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingExport = q
            pendingTargetBytes = targetBytes
            pendingLoop = loop
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        if (targetBytes > 0) beginExportBySize(targetBytes, loop, true)
        else if (q != null) beginExport(q, loop, true)
    }

    /** @param saveGallery 是否保存到系统相册（无权限时仅保存到应用内作品） */
    private fun beginExport(q: ExportEngine.Quality, loopCount: Int, saveGallery: Boolean) {
        // 预估阶段已经编码过一次的话直接复用，省掉重复计算
        val cached = estimateBytes["${q.name}:$loopCount"]
        if (cached != null) {
            Thread {
                try {
                    saveAndShow(cached, saveGallery, null)
                } catch (e: Exception) {
                    runOnUiThread { Ui.alert(this, "导出失败", e.message ?: "未知错误") }
                }
            }.start()
            return
        }
        val dialog = Ui.loadingDialog(this, "正在生成 GIF…")
        dialog.show()
        Thread {
            try {
                val bytes = ExportEngine.export(project, q, loopCount)
                saveAndShow(bytes, saveGallery, dialog)
            } catch (e: Exception) {
                runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    Ui.alert(this, "导出失败", e.message ?: "未知错误")
                }
            }
        }.start()
    }

    /** 按目标体积导出，实时显示进度 */
    private fun beginExportBySize(targetBytes: Long, loopCount: Int, saveGallery: Boolean) {
        val mb = targetBytes / 1024f / 1024f
        val ctx = this
        val progressText = Ui.text(ctx, "正在编码…", 13f, 0xFF1B1D22.toInt()).apply { gravity = Gravity.CENTER }
        val bar = android.widget.ProgressBar(ctx).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(0xFFFF6B3D.toInt())
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 22f), Ui.dp(ctx, 22f), Ui.dp(ctx, 22f), Ui.dp(ctx, 22f))
        }
        box.addView(Ui.text(ctx, String.format(Locale.US, "按 %.1f MB 生成中", mb), 14f, 0xFF1B1D22.toInt(), true))
        box.addView(bar, LinearLayout.LayoutParams(Ui.dp(ctx, 200f), -2).apply { topMargin = Ui.dp(ctx, 12f) })
        box.addView(progressText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(ctx, 8f) })
        val dialog = android.app.Dialog(ctx)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(box)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawable(Ui.rounded(Color.WHITE, 18f, ctx))
        dialog.show()

        Thread {
            try {
                val bytes = ExportEngine.exportWithinSize(project, targetBytes, loopCount) { pct ->
                    runOnUiThread {
                        bar.progress = pct
                        progressText.text = "已完成 $pct%"
                    }
                }
                runOnUiThread { if (dialog.isShowing) dialog.dismiss() }
                saveAndShow(bytes, saveGallery, null)
            } catch (e: Exception) {
                runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    Ui.alert(this, "导出失败", e.message ?: "未知错误")
                }
            }
        }.start()
    }

    private fun saveAndShow(bytes: ByteArray, saveGallery: Boolean, dialog: android.app.Dialog?) {
        val file = GifStore.saveToApp(this, bytes, project.name)
        if (saveGallery) GifStore.saveToGallery(this, bytes, file.nameWithoutExtension)
        exportedOnce = true
        runOnUiThread {
            if (dialog != null && dialog.isShowing) dialog.dismiss()
            showResult(file, bytes, saveGallery)
        }
    }

    private fun showResult(file: File, bytes: ByteArray, savedToGallery: Boolean) {
        val sizeMb = bytes.size / 1024f / 1024f
        val msg = buildString {
            append("文件大小：")
            append(String.format(Locale.US, "%.2f MB", sizeMb))
            append("\n尺寸：")
            append(gifSizeOf(bytes))
            append(" · 循环 ")
            append(if (project.loopCount <= 0) "无限" else "${project.loopCount} 次")
            append("\n已保存到「我的作品」")
            if (savedToGallery) append("，并已存入相册 Pictures/GifStudio")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("导出成功")
            .setMessage(msg)
            .setPositiveButton("分享") { _, _ -> GifStore.share(this, file) }
            .setNegativeButton("完成") { _, _ ->
                finish()
            }
            .setNeutralButton("继续编辑", null)
            .show()
    }

    private fun gifSizeOf(bytes: ByteArray): String {
        if (bytes.size < 10) return "未知"
        val w = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        val h = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        return "$w×$h"
    }

    override fun onDestroy() {
        preview.release()
        for (b in previewFrames) if (!b.isRecycled) b.recycle()
        for (b in thumbs) if (!b.isRecycled) b.recycle()
        previewFrames.clear()
        thumbs.clear()
        if (isFinishing && ProjectHolder.current === holder) {
            ProjectHolder.current = null
        }
        super.onDestroy()
    }
}
