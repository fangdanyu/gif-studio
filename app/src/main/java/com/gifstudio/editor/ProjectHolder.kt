package com.gifstudio.editor

import android.graphics.Bitmap

/** 在 Activity 之间传递当前编辑项目（位图不走序列化） */
object ProjectHolder {
    var current: GifProjectHolder? = null
}

class GifProjectHolder(val project: com.gifstudio.editor.model.GifProject) {
    fun release() {
        for (f in project.sourceFrames) {
            if (!f.isRecycled) f.recycle()
        }
        project.sourceFrames.clear()
    }
}
