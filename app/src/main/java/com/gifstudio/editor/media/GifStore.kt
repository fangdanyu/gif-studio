package com.gifstudio.editor.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.gifstudio.editor.gif.GifDecoder
import java.io.File
import kotlin.math.max

/** 文件保存、作品列表、分享 */
object GifStore {

    data class Item(val file: File, val name: String, val sizeBytes: Long, val modifiedMs: Long)

    fun appDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "gif")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun cacheDir(ctx: Context): File {
        val dir = File(ctx.cacheDir, "gif")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun uniqueName(baseName: String): String {
        val clean = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "GIF_Studio" }
        val noExt = if (clean.endsWith(".gif", true)) clean.dropLast(4) else clean
        return "$noExt.gif"
    }

    fun saveToApp(ctx: Context, bytes: ByteArray, name: String): File {
        var file = File(appDir(ctx), uniqueName(name))
        var seq = 1
        while (file.exists()) {
            file = File(appDir(ctx), uniqueName(name).dropLast(4) + "_${seq++}.gif")
        }
        file.writeBytes(bytes)
        return file
    }

    /** 保存到系统相册（Pictures/GifStudio），无需权限（Android 10+） */
    fun saveToGallery(ctx: Context, bytes: ByteArray, name: String): Uri? {
        val fileName = uniqueName(name)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GifStudio")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "GifStudio"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                file.writeBytes(bytes)
                MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf("image/gif"), null)
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun listSaved(ctx: Context): List<Item> {
        val dir = appDir(ctx)
        return (dir.listFiles { f -> f.isFile && f.name.endsWith(".gif", true) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { Item(it, it.name, it.length(), it.lastModified()) }
    }

    fun share(ctx: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/gif"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "分享 GIF"))
    }

    /** 用于分享的临时文件（放到 cache，供 FileProvider 读取） */
    fun prepareShareFile(ctx: Context, bytes: ByteArray, name: String): File {
        val file = File(cacheDir(ctx), uniqueName(name))
        file.writeBytes(bytes)
        return file
    }

    fun delete(file: File): Boolean = try {
        file.delete()
    } catch (e: Exception) {
        false
    }

    /** 读取 GIF 首帧作为缩略图 */
    fun thumbnail(file: File, maxEdge: Int = 240): Bitmap? {
        return try {
            val bytes = file.readBytes()
            val size = GifDecoder.probeSize(bytes) ?: return null
            var scale = 1
            while (max(size[0], size[1]) / scale > maxEdge && scale < 8) scale *= 2
            val decoded = GifDecoder.decode(bytes, scale, 1, 4_000_000L) ?: return null
            val pixels = decoded.frames.firstOrNull() ?: return null
            Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
            }
        } catch (e: Exception) {
            null
        }
    }
}
