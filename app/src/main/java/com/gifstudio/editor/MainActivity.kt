package com.gifstudio.editor

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.gifstudio.editor.media.GifStore
import com.gifstudio.editor.media.MediaLoader
import com.gifstudio.editor.media.ProjectFactory
import com.gifstudio.editor.ui.Ui
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30)
    ) { uris ->
        if (uris.isNotEmpty()) showImageDelayDialog(uris)
    }

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) openVideoFlow(uri)
    }

    private val pickGif = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) openGif(uri)
    }

    private val captureVideo = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) openVideoFlow(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
    }

    // ------------------------------------------------------------ 首页 UI

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.WHITE) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 20f), Ui.dp(this@MainActivity, 28f),
                Ui.dp(this@MainActivity, 20f), Ui.dp(this@MainActivity, 36f))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        Ui.applySystemBarInsets(this)

        root.addView(Ui.text(this, "GIF Studio", 25f, 0xFF1B1D22.toInt(), true))
        root.addView(Ui.text(
            this, "GIF 制作与编辑 · 视频/图片一键转 GIF",
            12.5f, 0xFF8A8F99.toInt()
        ).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(this@MainActivity, 6f)
            layoutParams = lp
        })

        // 主推卡片
        root.addView(Ui.featureCard(
            this, "视频转 GIF", "选取片段，自动抽帧生成动图", "🎬",
            0xFFFF8A3D.toInt(), 0xFFFF4E6B.toInt()
        ) { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            cardLp(16f))

        root.addView(Ui.featureCard(
            this, "多图合成 GIF", "2~30 张图片，选图即成片", "🖼",
            0xFF7C4DFF.toInt(), 0xFF3D7BFF.toInt()
        ) { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            cardLp(12f))

        root.addView(Ui.featureCard(
            this, "拍摄转 GIF", "打开相机录制，直接导入编辑", "📷",
            0xFF12B7A4.toInt(), 0xFF3DBEFF.toInt()
        ) { launchCapture() }, cardLp(12f))

        // 双列快捷入口
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        quickRow.addView(quickCard("打开 GIF", "导入已有动图再编辑", "✏️", 0xFFFF6B3D.toInt()) {
            pickGif.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = Ui.dp(this@MainActivity, 6f)
        })
        quickRow.addView(quickCard("模板库", "6 款热门表情包模板", "✨", 0xFF7C4DFF.toInt()) {
            startActivity(Intent(this, TemplateActivity::class.java))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Ui.dp(this@MainActivity, 6f)
        })
        root.addView(quickRow, cardLp(12f))

        quickRow2(root)

        root.addView(Ui.space(this, 24f))
        root.addView(Ui.divider(this))
        root.addView(Ui.text(
            this,
            "提示：导出后同时保存到相册 Pictures/GifStudio 与「我的作品」。",
            11.5f, 0xFF9AA0AA.toInt()
        ).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(this@MainActivity, 12f)
            layoutParams = lp
        })
        root.addView(Ui.text(this, "GIF 编解码 · 帧量化 · LZW 全部本地处理，不上传云端。", 11.5f, 0xFF9AA0AA.toInt()).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(this@MainActivity, 6f)
            layoutParams = lp
        })

        // 页脚：产品设计 / 版本
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.1.0"
        } catch (e: Exception) {
            "1.1.0"
        }
        val footer = Ui.text(this, "产品设计：FDY　版本：$versionName", 11.5f, 0xFFB3B8C2.toInt()).apply {
            gravity = Gravity.CENTER
        }
        root.addView(footer, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = Ui.dp(this@MainActivity, 18f)
        })
    }

    private fun quickRow2(root: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(quickCard("我的作品", "管理与分享已导出 GIF", "🗂", 0xFF3D7BFF.toInt()) {
            startActivity(Intent(this, GalleryActivity::class.java))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = Ui.dp(this@MainActivity, 6f)
        })
        row.addView(quickCard("使用说明", "各功能怎么用", "❓", 0xFF12B7A4.toInt()) {
            Ui.alert(
                this, "使用说明",
                "1. 视频转 GIF：选视频 → 拖动滑杆截取片段 → 选择帧率 → 生成。\n" +
                    "2. 多图合成：选 2~30 张图片后直接合成（默认每帧 200ms，可进编辑页再调）。\n" +
                    "3. 编辑页：支持拖拽框选裁剪（1:1、3:4、4:3、16:9、9:16 固定比例）、尺寸、速度、倒放、滤镜、涂鸦、文字、贴纸、帧时长。\n" +
                    "4. 文字、表情贴纸、图片贴纸：单指拖动位置，拖右下角圆点或双指捏合缩放，双指旋转角度。\n" +
                    "5. 贴纸可从相册导入自己的图片。\n" +
                    "6. 长按帧条或点「选帧」可多选并批量删除帧。\n" +
                    "7. 导出：高清/标准/压缩会实时显示预估体积，或按目标体积 0.5~10MB 自动压缩，并可设置循环次数。\n" +
                    "8. 未导出就返回时会提示先导出或放弃。"
            )
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Ui.dp(this@MainActivity, 6f)
        })
        root.addView(row, cardLp(12f))
    }

    private fun cardLp(topDp: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@MainActivity, topDp) }

    private fun quickCard(title: String, subtitle: String, emoji: String, accent: Int, onClick: () -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(0xFFF7F8FA.toInt(), 16f, this@MainActivity)
            setPadding(Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 14f),
                Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 14f))
            isClickable = true
            setOnClickListener { onClick() }
        }
        card.addView(Ui.text(this, emoji, 22f, accent))
        card.addView(Ui.text(this, title, 14f, 0xFF1B1D22.toInt(), true).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(this@MainActivity, 8f)
            layoutParams = lp
        })
        card.addView(Ui.text(this, subtitle, 11f, 0xFF8A8F99.toInt()).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(this@MainActivity, 4f)
            layoutParams = lp
        })
        return card
    }

    // ------------------------------------------------------------ 视频/图片流程

    private fun launchCapture() {
        try {
            val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
                putExtra(MediaStore.EXTRA_DURATION_LIMIT, 15)
            }
            captureVideo.launch(intent)
        } catch (e: Exception) {
            Ui.alert(this, "无法调用相机", "没有找到可用的相机应用")
        }
    }

    private fun openVideoFlow(uri: Uri) {
        val duration = MediaLoader.videoDurationMs(this, uri)
        if (duration <= 0) {
            Ui.alert(this, "无法读取视频", "该视频可能已损坏或格式不受支持")
            return
        }
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 8f), Ui.dp(ctx, 20f), Ui.dp(ctx, 8f))
        }

        val startText = Ui.text(ctx, "", 13f, 0xFFFF6B3D.toInt(), true)
        val lenText = Ui.text(ctx, "", 13f, 0xFFFF6B3D.toInt(), true)

        var startMs = 0L
        var lengthMs = minOf(3000L, duration)

        root.addView(Ui.text(ctx, "片段起点", 12.5f, 0xFF8A8F99.toInt()))
        val startSeek = SeekBar(ctx).apply { max = (duration - 500).toInt().coerceAtLeast(1) }
        startSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                startMs = progress.toLong()
                if (startMs + lengthMs > duration) startMs = (duration - lengthMs).coerceAtLeast(0)
                startText.text = String.format(Locale.US, "起点 %.1fs / 总长 %.1fs", startMs / 1000f, duration / 1000f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(startSeek)
        root.addView(startText)

        root.addView(Ui.text(ctx, "片段长度", 12.5f, 0xFF8A8F99.toInt()).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(ctx, 10f)
            layoutParams = lp
        })
        val lenSeek = SeekBar(ctx).apply { max = 55; progress = ((lengthMs - 500) / 100).toInt().coerceAtLeast(0) }
        lenSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                lengthMs = (500 + progress * 100).toLong()
                if (startMs + lengthMs > duration) startMs = (duration - lengthMs).coerceAtLeast(0)
                lenText.text = String.format(Locale.US, "%.1fs", lengthMs / 1000f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(lenSeek)
        root.addView(lenText)

        var fps = 12
        root.addView(Ui.text(ctx, "帧率", 12.5f, 0xFF8A8F99.toInt()).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(ctx, 10f)
            layoutParams = lp
        })
        val fpsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val fpsChips = mutableListOf<TextView>()
        fun refreshFps() {
            for ((i, c) in fpsChips.withIndex()) {
                val v = listOf(8, 10, 12, 15)[i]
                val sel = v == fps
                c.background = if (sel) Ui.gradient(0xFFFF8A3D.toInt(), 0xFFFF4E6B.toInt(), 16f, ctx)
                else Ui.rounded(0xFFF1F3F7.toInt(), 16f, ctx)
                c.setTextColor(if (sel) Color.WHITE else 0xFF3A3F47.toInt())
            }
        }
        for (v in listOf(8, 10, 12, 15)) {
            val chip = Ui.text(ctx, "${v}fps", 12.5f, 0xFF3A3F47.toInt()).apply {
                setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 7f), Ui.dp(ctx, 12f), Ui.dp(ctx, 7f))
                isClickable = true
                setOnClickListener { fps = v; refreshFps() }
            }
            fpsChips.add(chip)
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.marginEnd = Ui.dp(ctx, 6f)
            fpsRow.addView(chip, lp)
        }
        refreshFps()
        root.addView(fpsRow)

        var maxEdge = 480
        root.addView(Ui.text(ctx, "输出宽度", 12.5f, 0xFF8A8F99.toInt()).apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(ctx, 10f)
            layoutParams = lp
        })
        val sizeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val sizeChips = mutableListOf<TextView>()
        fun refreshSize() {
            for ((i, c) in sizeChips.withIndex()) {
                val v = listOf(320, 480, 640)[i]
                val sel = v == maxEdge
                c.background = if (sel) Ui.gradient(0xFFFF8A3D.toInt(), 0xFFFF4E6B.toInt(), 16f, ctx)
                else Ui.rounded(0xFFF1F3F7.toInt(), 16f, ctx)
                c.setTextColor(if (sel) Color.WHITE else 0xFF3A3F47.toInt())
            }
        }
        for (v in listOf(320, 480, 640)) {
            val chip = Ui.text(ctx, "${v}px", 12.5f, 0xFF3A3F47.toInt()).apply {
                setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 7f), Ui.dp(ctx, 12f), Ui.dp(ctx, 7f))
                isClickable = true
                setOnClickListener { maxEdge = v; refreshSize() }
            }
            sizeChips.add(chip)
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.marginEnd = Ui.dp(ctx, 6f)
            sizeRow.addView(chip, lp)
        }
        refreshSize()
        root.addView(sizeRow)

        startText.text = String.format(Locale.US, "起点 %.1fs / 总长 %.1fs", 0f, duration / 1000f)
        lenText.text = String.format(Locale.US, "%.1fs", lengthMs / 1000f)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("视频转 GIF")
            .setView(ScrollView(ctx).apply { addView(root) })
            .setNegativeButton("取消", null)
            .setPositiveButton("生成") { _, _ ->
                generateFromVideo(uri, startMs, lengthMs, fps, maxEdge)
            }
            .show()
    }

    private fun generateFromVideo(uri: Uri, startMs: Long, lengthMs: Long, fps: Int, maxEdge: Int) {
        val dialog = Ui.loadingDialog(this, "正在提取视频帧…")
        dialog.show()
        Thread {
            val project = ProjectFactory.fromVideo(this, uri, startMs, lengthMs, fps, maxEdge)
            runOnUiThread {
                dialog.dismiss()
                if (project == null || project.frameCount == 0) {
                    Ui.alert(this, "生成失败", "未能从视频中提取到画面，请换一个视频或缩短片段")
                } else {
                    openEditor(project)
                }
            }
        }.start()
    }

    private fun showImageDelayDialog(uris: List<Uri>) {
        // 多图合成不弹窗选帧率：直接按默认 200ms 合成，进编辑页后可在「帧时长」统一调整
        generateFromImages(uris, 200)
    }

    private fun generateFromImages(uris: List<Uri>, delayMs: Int) {
        val dialog = Ui.loadingDialog(this, "正在合成 GIF…")
        dialog.show()
        Thread {
            val project = ProjectFactory.fromImages(this, uris, delayMs)
            runOnUiThread {
                dialog.dismiss()
                if (project == null) {
                    Ui.alert(this, "合成失败", "图片读取失败，请重试")
                } else {
                    openEditor(project)
                }
            }
        }.start()
    }

    private fun openGif(uri: Uri) {
        val dialog = Ui.loadingDialog(this, "正在解析 GIF…")
        dialog.show()
        Thread {
            val project = ProjectFactory.fromGif(this, uri)
            runOnUiThread {
                dialog.dismiss()
                if (project == null) {
                    Ui.alert(this, "打开失败", "无法解析该 GIF 文件")
                } else {
                    openEditor(project)
                }
            }
        }.start()
    }

    private fun openEditor(project: com.gifstudio.editor.model.GifProject) {
        ProjectHolder.current?.release()
        ProjectHolder.current = GifProjectHolder(project)
        startActivity(Intent(this, EditorActivity::class.java))
    }
}
