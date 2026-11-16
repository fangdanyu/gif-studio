package com.gifstudio.editor

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.gifstudio.editor.media.GifStore
import com.gifstudio.editor.media.ProjectFactory
import com.gifstudio.editor.ui.Ui
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.WHITE) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@GalleryActivity, 20f), Ui.dp(this@GalleryActivity, 24f),
                Ui.dp(this@GalleryActivity, 20f), Ui.dp(this@GalleryActivity, 30f))
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
            setPadding(0, 0, Ui.dp(this@GalleryActivity, 14f), 0)
        })
        header.addView(Ui.text(this, "我的作品", 18f, 0xFF1B1D22.toInt(), true))
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = Ui.dp(this@GalleryActivity, 14f)
        })

        root.addView(Ui.text(
            this, "作品保存在应用目录，同时已写入相册 Pictures/GifStudio", 11.5f, 0xFF8A8F99.toInt()
        ), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = Ui.dp(this@GalleryActivity, 16f)
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val items = GifStore.listSaved(this)
        if (items.isEmpty()) {
            list.addView(Ui.text(this, "还没有导出的作品\n回到首页做一个 GIF 吧", 13f, 0xFF8A8F99.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(this@GalleryActivity, 60f), 0, 0)
            })
            return
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        for (item in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = Ui.rounded(0xFFF7F8FA.toInt(), 16f, this@GalleryActivity)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(this@GalleryActivity, 12f), Ui.dp(this@GalleryActivity, 12f),
                    Ui.dp(this@GalleryActivity, 12f), Ui.dp(this@GalleryActivity, 12f))
            }
            val thumb = GifStore.thumbnail(item.file, 240)
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (thumb != null) setImageBitmap(thumb)
                background = Ui.rounded(0xFFE9EDF4.toInt(), 12f, this@GalleryActivity)
                clipToOutline = true
            }
            row.addView(iv, LinearLayout.LayoutParams(Ui.dp(this, 76f), Ui.dp(this, 76f)))

            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, -2, 1f)
                lp.marginStart = Ui.dp(this@GalleryActivity, 12f)
                layoutParams = lp
            }
            body.addView(Ui.text(this, item.name, 13.5f, 0xFF1B1D22.toInt(), true).apply { maxLines = 1 })
            body.addView(Ui.text(
                this,
                String.format(Locale.US, "%.1f KB · %s", item.sizeBytes / 1024f, sdf.format(Date(item.modifiedMs))),
                11f, 0xFF8A8F99.toInt()
            ).apply {
                val lp = LinearLayout.LayoutParams(-1, -2)
                lp.topMargin = Ui.dp(this@GalleryActivity, 4f)
                layoutParams = lp
            })
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(-1, -2)
                lp.topMargin = Ui.dp(this@GalleryActivity, 8f)
                layoutParams = lp
            }
            actions.addView(smallAction("分享") { GifStore.share(this, item.file) })
            actions.addView(smallAction("再编辑") { openEditor(item.file) })
            actions.addView(smallAction("删除", 0xFFB00020.toInt()) { confirmDelete(item.file) })
            body.addView(actions)
            row.addView(body)
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = Ui.dp(this@GalleryActivity, 12f)
            })
        }
    }

    private fun smallAction(label: String, color: Int = 0xFF3D7BFF.toInt(), onClick: () -> Unit): TextView =
        Ui.text(this, label, 12f, color, true).apply {
            setPadding(Ui.dp(this@GalleryActivity, 10f), Ui.dp(this@GalleryActivity, 4f),
                Ui.dp(this@GalleryActivity, 10f), Ui.dp(this@GalleryActivity, 4f))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun confirmDelete(file: java.io.File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除作品")
            .setMessage("删除后不可恢复（相册中的副本需自行删除），确定删除「${file.name}」？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                GifStore.delete(file)
                refresh()
            }
            .show()
    }

    private fun openEditor(file: java.io.File) {
        val dialog = Ui.loadingDialog(this, "正在打开作品…")
        dialog.show()
        Thread {
            val p = ProjectFactory.fromGif(this, Uri.fromFile(file))
            runOnUiThread {
                dialog.dismiss()
                if (p == null || p.frameCount == 0) {
                    Ui.alert(this, "打开失败", "该文件无法解析")
                } else {
                    // 去掉历史导出留下的 _1/_2 序号后缀，避免名字越叠越长
                    p.name = file.nameWithoutExtension.replace(Regex("_\\d+$"), "")
                    ProjectHolder.current?.release()
                    ProjectHolder.current = GifProjectHolder(p)
                    startActivity(Intent(this, EditorActivity::class.java))
                }
            }
        }.start()
    }
}
