package com.xnet.pulse.feature.chat.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttachmentProcessor @Inject constructor(@ApplicationContext private val ctx: Context) {

  data class Attachment(val type: String, val content: String, val name: String, val displayPath: String? = null)

  fun process(uri: Uri): Attachment? {
    val mime = ctx.contentResolver.getType(uri) ?: return null
    val name = uri.lastPathSegment ?: "file"
    return when {
      mime.startsWith("image/") -> processImage(uri, name)
      mime == "application/pdf" -> processPdf(uri, name)
      mime.startsWith("text/") || isCodeFile(name) -> processText(uri, name)
      else -> processText(uri, name) // fallback: try as text
    }
  }

  private fun processImage(uri: Uri, name: String): Attachment? {
    val input = ctx.contentResolver.openInputStream(uri) ?: return null
    val bytes = input.readBytes()
    input.close()
    // Use full normalization pipeline (SVG rasterize, resize, JPEG convert)
    val normalized = normalizeForVision(bytes, name)
    // Save to app files for stable rendering
    val file = java.io.File(ctx.filesDir, "images/${System.currentTimeMillis()}_$name.jpg")
    file.parentFile?.mkdirs()
    file.writeBytes(normalized)
    val filePath = "file://${file.absolutePath}"
    val b64 = Base64.encodeToString(normalized, Base64.NO_WRAP)
    return Attachment("image", "data:image/jpeg;base64,$b64", name, filePath)
  }

  fun normalizeForVision(data: ByteArray, url: String): ByteArray {
    val isSvg = url.lowercase().endsWith(".svg") ||
      (data.isNotEmpty() && (data[0] == '<'.code.toByte() || (data.size > 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte())))
    if (isSvg) {
      try {
        val svg = com.caverock.androidsvg.SVG.getFromInputStream(data.inputStream())
        val w = svg.documentWidth.takeIf { it > 0 } ?: 1024f
        val h = svg.documentHeight.takeIf { it > 0 } ?: 1024f
        val scale = 1024f / maxOf(w, h)
        val bw = (w * scale).toInt()
        val bh = (h * scale).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        svg.documentWidth = bw.toFloat()
        svg.documentHeight = bh.toFloat()
        svg.renderToCanvas(canvas)
        return bitmapToJpeg(bmp)
      } catch (_: Exception) {}
    }
    val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return data
    val scaled = scaleBitmap(bmp, 1024)
    val result = bitmapToJpeg(scaled)
    if (scaled !== bmp) bmp.recycle()
    return result
  }

  private fun bitmapToJpeg(bmp: android.graphics.Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
    bmp.recycle()
    return out.toByteArray()
  }

  private fun processText(uri: Uri, name: String): Attachment? {
    val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()?.take(50000) ?: return null
    return Attachment("text", text, name)
  }

  private fun processPdf(uri: Uri, name: String): Attachment? {
    // Basic PDF text extraction using PdfRenderer
    return try {
      val fd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
      val renderer = android.graphics.pdf.PdfRenderer(fd)
      val text = buildString {
        for (i in 0 until minOf(renderer.pageCount, 20)) {
          val page = renderer.openPage(i)
          // PdfRenderer doesn't extract text directly — would need pdfbox
          // For now, note the page count
          page.close()
        }
        append("[PDF: $name, ${renderer.pageCount} pages — text extraction requires pdfbox]")
      }
      renderer.close()
      fd.close()
      Attachment("text", text, name)
    } catch (e: Exception) { Attachment("text", "[Could not read PDF: ${e.message}]", name) }
  }

  private fun scaleBitmap(bmp: Bitmap, maxDim: Int): Bitmap {
    if (bmp.width <= maxDim && bmp.height <= maxDim) return bmp
    val ratio = minOf(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height)
    return Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true)
  }

  private fun isCodeFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in setOf("kt", "java", "py", "js", "ts", "json", "xml", "yaml", "yml", "toml", "sh", "md", "txt", "csv", "html", "css", "sql", "go", "rs")
  }
}
