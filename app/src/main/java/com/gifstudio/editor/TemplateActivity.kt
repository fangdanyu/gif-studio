package com.gifstudio.editor

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.gifstudio.editor.media.GifTemplate
import com.gifstudio.editor.media.ProjectFactory
import com.gifstudio.editor.media.Templates
import com.gifstudio.editor.ui.Ui

class TemplateActivity : AppCompatActivity() {

    private var pendingTemplate: GifTemplate? = null
    private var pendingDelay = 200

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(12)
    ) { uris ->
        val template = pendingTemplate
        pendingTemplate = null
        if (uris.isNotEmpty() && template != null) buildWithTemplate(template, uris, pendingDelay)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.WHITE) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@TemplateActivity, 20f), Ui.dp(this@TemplateActivity, 24f),
                Ui.dp(this@TemplateActivity, 20f), Ui.dp(this@TemplateActivity, 30f))
        }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))
        setContentView(scroll)
        Ui.applySystemBarInsets(this)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Ui.text(this, "‹", 22f, 0xFF1B1D22.toInt(), true).apply {
            isClickable = true
            setOnClickListener { finish() }
            setPadding(0, 0, Ui.dp(this@TemplateActivity, 14f), 0)
        })
        header.addView(Ui.text(this, "表情包模板", 18f, 0xFF1B1D22.toInt(), true))
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = Ui.dp(this@TemplateActivity, 8f)
        })
        root.addView(Ui.text(
            this, "选一个模板 → 选 2~12 张图片 → 自动套用文字/贴纸/滤镜", 11.5f, 0xFF8A8F99.toInt()
        ), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = Ui.dp(this@TemplateActivity, 18f)
        })

        for (t in Templates.all) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = Ui.gradient(t.gradientStart, t.gradientEnd, 18f, this@TemplateActivity)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(this@TemplateActivity, 16f), Ui.dp(this@TemplateActivity, 16f),
                    Ui.dp(this@TemplateActivity, 16f), Ui.dp(this@TemplateActivity, 16f))
                isClickable = true
                setOnClickListener { pendingTemplate = t; pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            }
            card.addView(Ui.text(this, t.emoji, 26f, Color.WHITE))
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, -2, 1f)
                lp.marginStart = Ui.dp(this@TemplateActivity, 12f)
                layoutParams = lp
            }
            body.addView(Ui.text(this, t.title, 15f, Color.WHITE, true))
            body.addView(Ui.text(this, "「${t.caption}」 · ${t.desc}", 11f, 0xCCFFFFFF.toInt()).apply {
                val lp = LinearLayout.LayoutParams(-1, -2)
                lp.topMargin = Ui.dp(this@TemplateActivity, 4f)
                layoutParams = lp
            })
            card.addView(body)
            card.addView(Ui.text(this, "›", 20f, Color.WHITE, true))
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = Ui.dp(this@TemplateActivity, 12f)
            })
        }
    }

    private fun buildWithTemplate(template: GifTemplate, uris: List<Uri>, delayMs: Int) {
        val dialog = Ui.loadingDialog(this, "正在套用模板…")
        dialog.show()
        Thread {
            val project = ProjectFactory.fromImages(this, uris, delayMs)
            runOnUiThread {
                dialog.dismiss()
                if (project == null) {
                    Ui.alert(this, "生成失败", "图片读取失败，请重试")
                } else {
                    ProjectFactory.applyTemplate(project, template)
                    project.name = "GIF_Studio_${template.id}"
                    ProjectHolder.current?.release()
                    ProjectHolder.current = GifProjectHolder(project)
                    startActivity(Intent(this, EditorActivity::class.java))
                }
            }
        }.start()
    }
}
