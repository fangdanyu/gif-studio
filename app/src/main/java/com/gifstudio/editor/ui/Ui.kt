package com.gifstudio.editor.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

/** 轻量 UI 构件工厂，保证各页面视觉一致 */
object Ui {

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).roundToInt()

    fun text(
        ctx: Context,
        s: CharSequence,
        sizeSp: Float = 14f,
        color: Int = 0xFF1B1D22.toInt(),
        bold: Boolean = false
    ): TextView = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        includeFontPadding = false
    }

    fun rounded(color: Int, radiusDp: Float, ctx: Context, strokeWidth: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(dp(ctx, strokeWidth.toFloat()), strokeColor)
        }

    fun gradient(start: Int, end: Int, radiusDp: Float, ctx: Context, orientation: GradientDrawable.Orientation =
        GradientDrawable.Orientation.LEFT_RIGHT): GradientDrawable =
        GradientDrawable(orientation, intArrayOf(start, end)).apply {
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    fun pillButton(ctx: Context, label: String, start: Int, end: Int): TextView = text(ctx, label, 14f, Color.WHITE, true).apply {
        background = gradient(start, end, 22f, ctx)
        gravity = Gravity.CENTER
        val ph = dp(ctx, 18f)
        val pv = dp(ctx, 9f)
        setPadding(ph, pv, ph, pv)
        isClickable = true
    }

    fun chip(ctx: Context, label: String, selected: Boolean = false): TextView =
        text(ctx, label, 13.5f, if (selected) Color.WHITE else 0xFF3A3F47.toInt(), selected).apply {
            background = if (selected) {
                gradient(0xFFFF8A3D.toInt(), 0xFFFF4E6B.toInt(), 18f, ctx)
            } else {
                rounded(0xFFF1F3F7.toInt(), 18f, ctx)
            }
            gravity = Gravity.CENTER
            val ph = dp(ctx, 14f)
            val pv = dp(ctx, 7f)
            setPadding(ph, pv, ph, pv)
            isClickable = true
        }

    /** 首页功能卡片 */
    fun featureCard(
        ctx: Context,
        title: String,
        subtitle: String,
        emoji: String,
        start: Int,
        end: Int,
        onClick: () -> Unit
    ): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = gradient(start, end, 20f, ctx)
            gravity = Gravity.CENTER_VERTICAL
            val p = dp(ctx, 16f)
            setPadding(p, p, p, p)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val emojiView = text(ctx, emoji, 26f, Color.WHITE).apply {
            gravity = Gravity.CENTER
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(ctx, 12f)
            layoutParams = lp
        }
        body.addView(text(ctx, title, 16f, Color.WHITE, true))
        body.addView(text(ctx, subtitle, 11.5f, 0xCCFFFFFF.toInt()).apply {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, 4f)
            layoutParams = lp
        })
        val arrow = text(ctx, "›", 20f, Color.WHITE, true)
        card.addView(emojiView, LinearLayout.LayoutParams(dp(ctx, 34f), dp(ctx, 34f)))
        card.addView(body)
        card.addView(arrow)
        return card
    }

    fun space(ctx: Context, heightDp: Float): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, heightDp))
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(0xFFEDEFF3.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 0.7f))
    }

    fun loadingDialog(ctx: Context, message: String): Dialog {
        val ll = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = dp(ctx, 26f)
            setPadding(p, p, p, p)
        }
        val progress = android.widget.ProgressBar(ctx).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFFF6B3D.toInt())
        }
        val tv = text(ctx, message, 14f, 0xFF1B1D22.toInt()).apply {
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, 14f)
            layoutParams = lp
        }
        ll.addView(progress, LinearLayout.LayoutParams(dp(ctx, 40f), dp(ctx, 40f)))
        ll.addView(tv)
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(ll)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawable(rounded(Color.WHITE, 18f, ctx))
        return dialog
    }

    fun toast(ctx: Context, msg: String) {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Android 15+（targetSdk 35 起）强制「边到边」显示，内容会画到状态栏/导航栏下面。
     * 给 Activity 根视图补上系统栏内边距，避免标题、按钮被状态栏图标压住。
     */
    fun applySystemBarInsets(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        if (content == null || content.childCount == 0) return
        val root = content.getChildAt(0)
        val pl = root.paddingLeft
        val pt = root.paddingTop
        val pr = root.paddingRight
        val pb = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(pl + bars.left, pt + bars.top, pr + bars.right, pb + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun alert(ctx: Context, title: String, message: String) {
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }
}
