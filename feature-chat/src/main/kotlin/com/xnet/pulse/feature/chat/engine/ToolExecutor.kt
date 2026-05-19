package com.xnet.pulse.feature.chat.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.xnet.pulse.core.model.ToolDef
import com.xnet.pulse.core.network.PollinationsClient
import com.xnet.pulse.feature.chat.db.ChatDao
import com.xnet.pulse.feature.chat.db.MemoryEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolExecutor @Inject constructor(
  @ApplicationContext private val ctx: Context,
  private val dao: ChatDao,
  private val pollinationsClient: PollinationsClient,
) {
  var conversationId: String = ""
  private val sandboxRoot: File get() = File(ctx.filesDir, "pulse").also { it.mkdirs() }
  private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
  private var searchUrl = "https://search.xnet.ngo"

  fun buildToolDefs(): List<ToolDef> = listOf(
    ToolDef("search_web", "Search the web for current information.", """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""", true),
    ToolDef("search_images", "Search for images on the web.", """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""", true),
    ToolDef("fetch_url", "Fetch a URL. Modes: text (default), md, raw.", """{"type":"object","properties":{"url":{"type":"string"},"mode":{"type":"string"}},"required":["url"]}""", true),
    ToolDef("list_directory", "List directory contents.", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""", true),
    ToolDef("read_file", "Read file contents.", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""", true),
    ToolDef("write_file", "Write content to a file.", """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""", false),
    ToolDef("get_location", "Get device GPS location.", """{"type":"object","properties":{}}""", true),
    ToolDef("open_intent", "Open a URL, map, or app.", """{"type":"object","properties":{"uri":{"type":"string"}},"required":["uri"]}""", false),
    ToolDef("analyze_image", "Analyze an image using vision.", """{"type":"object","properties":{"url":{"type":"string"},"question":{"type":"string"}},"required":["url"]}""", true),
    ToolDef("image_generate", "Generate an image from a text prompt.", """{"type":"object","properties":{"prompt":{"type":"string"}},"required":["prompt"]}""", true),
    ToolDef("memory_store", "Remember a fact across conversations.", """{"type":"object","properties":{"key":{"type":"string"},"content":{"type":"string"},"category":{"type":"string"}},"required":["key","content"]}""", true),
    ToolDef("memory_recall", "Search memory. Empty query lists all.", """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""", true),
  )

  suspend fun execute(name: String, args: Map<String, Any?>): String = when (name) {
    "search_web" -> searchWeb(args["query"]?.toString() ?: "")
    "search_images" -> searchImages(args["query"]?.toString() ?: "")
    "fetch_url" -> fetchUrl(args["url"]?.toString() ?: "", args["mode"]?.toString() ?: "text")
    "list_directory" -> listDirectory(args["path"]?.toString() ?: "/")
    "read_file" -> readFile(args["path"]?.toString() ?: "")
    "write_file" -> writeFile(args["path"]?.toString() ?: "", args["content"]?.toString() ?: "")
    "get_location" -> getLocation()
    "open_intent" -> openIntent(args["uri"]?.toString() ?: "")
    "analyze_image" -> analyzeImage(args["url"]?.toString() ?: "", args["question"]?.toString())
    "image_generate" -> imageGenerate(args["prompt"]?.toString() ?: "")
    "memory_store" -> memoryStore(args["key"]?.toString() ?: "", args["content"]?.toString() ?: "", args["category"]?.toString() ?: "general")
    "memory_recall" -> memoryRecall(args["query"]?.toString() ?: "")
    else -> "Unknown tool: $name"
  }

  private suspend fun searchWeb(query: String): String = withContext(Dispatchers.IO) {
    val req = Request.Builder().url("$searchUrl/search?q=${Uri.encode(query)}&format=json&categories=general").build()
    val body = http.newCall(req).execute().use { it.body?.string() ?: "" }
    val results = JSONObject(body).optJSONArray("results") ?: return@withContext "No results"
    buildString {
      for (i in 0 until minOf(results.length(), 8)) {
        val r = results.getJSONObject(i)
        appendLine("${r.optString("title")}\n${r.optString("url")}\n${r.optString("content")}\n")
      }
    }
  }

  private suspend fun searchImages(query: String): String = withContext(Dispatchers.IO) {
    val req = Request.Builder().url("$searchUrl/search?q=${Uri.encode(query)}&format=json&categories=images").build()
    val body = http.newCall(req).execute().use { it.body?.string() ?: "" }
    val results = JSONObject(body).optJSONArray("results") ?: return@withContext "No results"
    buildString {
      for (i in 0 until minOf(results.length(), 6)) {
        val r = results.getJSONObject(i)
        appendLine("![${r.optString("title")}](${r.optString("img_src")})")
      }
    }
  }

  private suspend fun fetchUrl(url: String, mode: String): String = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(url).header("User-Agent", "AIOPulse/1.0").build()
    val resp = http.newCall(req).execute()
    val body = resp.body?.string()?.take(12000) ?: ""
    when (mode) {
      "raw" -> body
      "md" -> body // TODO: HTML→markdown conversion
      else -> body.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
    }
  }

  private fun listDirectory(path: String): String {
    val dir = File(DirectoryManager.workspace(conversationId), path.removePrefix("/"))
    if (!dir.exists()) return "Directory not found: $path"
    return dir.listFiles()?.joinToString("\n") { (if (it.isDirectory) "📁 " else "📄 ") + it.name } ?: "Empty"
  }

  private fun readFile(path: String): String {
    val file = File(DirectoryManager.workspace(conversationId), path.removePrefix("/"))
    if (!file.exists()) return "File not found: $path"
    return file.readText().take(50000)
  }

  private fun writeFile(path: String, content: String): String {
    val dir = DirectoryManager.workspace(conversationId)
    val file = File(dir, path.removePrefix("/"))
    file.parentFile?.mkdirs()
    file.writeText(content)
    return "Written ${content.length} chars to ${file.name}"
  }

  private fun getLocation(): String {
    return try {
      val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
      val loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
        ?: lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
        ?: return "Location unavailable — enable GPS or grant permission"
      "Latitude: ${loc.latitude}, Longitude: ${loc.longitude}, Accuracy: ${loc.accuracy}m"
    } catch (e: SecurityException) { "Location permission not granted" }
  }

  private fun openIntent(uri: String): String {
    return try {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      ctx.startActivity(intent)
      "Opened: $uri"
    } catch (e: Exception) { "Failed to open: ${e.message}" }
  }
  private suspend fun analyzeImage(url: String, question: String?): String = withContext(Dispatchers.IO) {
    try {
      val imgData = fetchImageBytes(url)
      val normalized = normalizeForVision(imgData, url)
      val b64 = android.util.Base64.encodeToString(normalized, android.util.Base64.NO_WRAP)
      val dataUri = "data:image/jpeg;base64,$b64"
      val msgs = listOf(
        JSONObject().put("role", "user").put("content", JSONArray().apply {
          put(JSONObject().put("type", "text").put("text", question ?: "Describe this image in detail."))
          put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUri)))
        })
      )
      val sb = StringBuilder()
      pollinationsClient.stream(msgs, "openai").collect { ev ->
        if (ev is com.xnet.pulse.core.model.StreamEvent.Delta) sb.append(ev.text)
      }
      sb.toString().ifBlank { "Could not analyze image" }
    } catch (e: Exception) {
      "Image analysis failed: ${e.message}"
    }
  }

  private fun fetchImageBytes(url: String): ByteArray {
    if (url.startsWith("file://")) return java.io.File(url.removePrefix("file://")).readBytes()
    if (url.startsWith("/")) return java.io.File(url).readBytes()
    val req = Request.Builder().url(url)
      .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0")
      .header("Accept", "image/*,*/*")
      .build()
    val resp = http.newCall(req).execute()
    if (resp.code != 200) throw Exception("HTTP ${resp.code} from $url")
    return resp.body?.bytes() ?: throw Exception("Empty response")
  }

  private fun normalizeForVision(data: ByteArray, url: String): ByteArray {
    // SVG detection
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
    // Decode any format Android supports
    val bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) ?: return data
    val maxDim = 1024
    val s = if (bmp.width > maxDim || bmp.height > maxDim) maxDim.toFloat() / maxOf(bmp.width, bmp.height) else 1f
    val scaled = if (s < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
    val result = bitmapToJpeg(scaled)
    if (scaled !== bmp) bmp.recycle()
    return result
  }

  private fun bitmapToJpeg(bmp: android.graphics.Bitmap): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
    bmp.recycle()
    return out.toByteArray()
  }

  private suspend fun imageGenerate(prompt: String): String {
    val url = pollinationsClient.imageUrl(prompt)
    val filename = "gen_${System.currentTimeMillis()}.png"
    val file = File(DirectoryManager.generated(conversationId), filename)
    withContext(Dispatchers.IO) {
      val req = Request.Builder().url(url).build()
      http.newCall(req).execute().use { resp ->
        resp.body?.byteStream()?.use { input -> file.outputStream().use { input.copyTo(it) } }
      }
    }
    return "![Generated image](file://${file.absolutePath})"
  }

  private suspend fun memoryStore(key: String, content: String, category: String): String {
    dao.upsertMemory(MemoryEntity(key = key, content = content, category = category, updatedAt = System.currentTimeMillis()))
    return "Remembered: $key"
  }

  private suspend fun memoryRecall(query: String): String {
    val memories = if (query.isBlank()) dao.getAllMemories() else dao.searchMemories(query)
    if (memories.isEmpty()) return "No memories found"
    return memories.joinToString("\n") { "• ${it.key}: ${it.content} [${it.category}]" }
  }

}
