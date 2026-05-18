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

  data class Attachment(val type: String, val content: String, val name: String)

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
    val bitmap = BitmapFactory.decodeStream(input) ?: return null
    input.close()
    val scaled = scaleBitmap(bitmap, 1024)
    val baos = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
    val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    return Attachment("image", "data:image/jpeg;base64,$b64", name)
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
