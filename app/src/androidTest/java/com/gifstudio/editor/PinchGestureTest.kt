package com.gifstudio.editor

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gifstudio.editor.model.GifProject
import com.gifstudio.editor.model.StickerOverlay
import com.gifstudio.editor.view.GifPreviewView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 用真实 MotionEvent 验证「双指捏合缩放 + 旋转」的触摸处理链路。
 * （adb 的 input tap/swipe 只能产生单点，无 root 也无法 sendevent 写 /dev/input，
 *  因此在设备内直接向 GifPreviewView 投递双指事件，走的是真实 onTouchEvent 分支。）
 */
@RunWith(AndroidJUnit4::class)
class PinchGestureTest {

    private fun frame(w: Int, h: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    private fun event(
        action: Int,
        downTime: Long,
        eventTime: Long,
        points: List<Pair<Float, Float>>
    ): MotionEvent {
        val props = Array(points.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(points.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = points[i].first
                y = points[i].second
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            downTime, eventTime, action, points.size, props, coords,
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
    }

    @Test
    fun pinchScalesAndRotatesSelectedSticker() {
        val project = GifProject()
        project.sourceFrames.add(frame(240, 240, Color.WHITE))
        project.sourceFrames.add(frame(240, 240, Color.LTGRAY))
        project.sourceDelays.add(100)
        project.sourceDelays.add(100)
        project.stickers.add(StickerOverlay(emoji = "⭐", cx = 0.5f, cy = 0.5f, sizeRatio = 0.20f))
        ProjectHolder.current = GifProjectHolder(project)

        ActivityScenario.launch(EditorActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val preview = activity.findViewById<GifPreviewView>(R.id.preview)
                preview.playing = false
                preview.editMode = GifPreviewView.MODE_LAYER
                assertTrue("预览视图需要有尺寸", preview.width > 0 && preview.height > 0)

                // 选中贴纸图层（等价于用户单击选中）
                preview.selectedKind = 1
                preview.selectedIndex = 0

                val cx = preview.width / 2f
                val cy = preview.height / 2f
                val t0 = SystemClock.uptimeMillis()
                val p0 = cx - 50f to cy
                val p1 = cx + 50f to cy

                preview.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, t0, t0, listOf(p0)))
                preview.dispatchTouchEvent(
                    event(
                        MotionEvent.ACTION_POINTER_DOWN or
                            (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        t0, t0 + 10, listOf(p0, p1)
                    )
                )
                // 双指张开到约 2.83 倍间距，同时整体旋转约 45°
                val q0 = cx - 100f to cy - 100f
                val q1 = cx + 100f to cy + 100f
                preview.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, t0, t0 + 40, listOf(q0, q1)))
                preview.dispatchTouchEvent(
                    event(
                        MotionEvent.ACTION_POINTER_UP or
                            (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        t0, t0 + 60, listOf(q0, q1)
                    )
                )
                preview.dispatchTouchEvent(event(MotionEvent.ACTION_UP, t0, t0 + 70, listOf(q1)))

                val st = project.stickers[0]
                // 初始 0.20，间距 100 → 282.8，约 2.83 倍
                assertEquals(0.20f * 2.828f, st.sizeRatio, 0.03f)
                // 初始 0°，张开后约 45°
                assertEquals(45f, st.rotation, 8f)
                // 双指中点未移动，中心应基本不变
                assertEquals(0.5f, st.cx, 0.03f)
                assertEquals(0.5f, st.cy, 0.03f)
            }
        }
        ProjectHolder.current = null
    }

    @Test
    fun fixedAspectCropProducesSquareOutput() {
        val project = GifProject()
        project.sourceFrames.add(frame(320, 180, Color.WHITE))
        project.sourceDelays.add(100)
        ProjectHolder.current = GifProjectHolder(project)

        ActivityScenario.launch(EditorActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val preview = activity.findViewById<GifPreviewView>(R.id.preview)
                preview.playing = false
                preview.editMode = GifPreviewView.MODE_CROP
                preview.setCropAspect(1f)
                assertEquals("1:1 选框左右方向占比", 0.5625f, preview.cropRect.width(), 0.02f)
                assertEquals("1:1 选框上下方向占比", 1f, preview.cropRect.height(), 0.02f)
                preview.applyCropToProject()

                val cropWpx = (project.cropRight - project.cropLeft) * 320f
                val cropHpx = (project.cropBottom - project.cropTop) * 180f
                assertEquals("1:1 裁剪后像素宽高比", 1f, cropWpx / cropHpx, 0.03f)
            }
        }
        ProjectHolder.current = null
    }
}
