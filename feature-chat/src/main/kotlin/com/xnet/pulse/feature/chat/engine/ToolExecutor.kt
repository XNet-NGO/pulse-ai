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
    ToolDef("fetch_url", "Fetch a URL and return extracted text content.", """{"type":"object","properties":{"url":{"type":"string"},"mode":{"type":"string","description":"text (default), raw, or image"}},"required":["url"]}""", true),
    ToolDef("read_file", "Read file contents.", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""", true),
    ToolDef("write_file", "Write file.", """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""", false),
    ToolDef("list_directory", "List directory.", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""", true),
    ToolDef("get_location", "Get device GPS location.", """{"type":"object","properties":{}}""", true),
    ToolDef("open_intent", "Open a URL, map, or app on device.", """{"type":"object","properties":{"uri":{"type":"string"}},"required":["uri"]}""", false),
    ToolDef("image_generate", "Generate an image from a text prompt.", """{"type":"object","properties":{"prompt":{"type":"string"}},"required":["prompt"]}""", true),
    ToolDef("memory_store", "Remember a fact across conversations.", """{"type":"object","properties":{"key":{"type":"string"},"content":{"type":"string"},"category":{"type":"string"}},"required":["key","content"]}""", true),
    ToolDef("memory_recall", "Search stored memories.", """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""", true),
    ToolDef("memory_forget", "Delete a memory by key.", """{"type":"object","properties":{"key":{"type":"string"}},"required":["key"]}""", false),
  )

  suspend fun execute(name: String, args: Map<String, Any?>): String = when (name) {
    "search_web", "search", "search_location", "query_data" -> search(args["query"]?.toString() ?: (args["category"]?.toString() ?: ""), "general", 8)
    "search_images" -> search(args["query"]?.toString() ?: "", "images", 8)
    "fetch_url" -> fetchUrl(args["url"]?.toString() ?: "", args["mode"]?.toString() ?: "text")
    "read_file" -> readFile(args["path"]?.toString() ?: "")
    "write_file" -> writeFile(args["path"]?.toString() ?: "", args["content"]?.toString() ?: "")
    "list_directory" -> listDirectory(args["path"]?.toString() ?: "/")
    "get_location" -> getLocation()
    "open_intent" -> openIntent(args["uri"]?.toString() ?: "")
    "image_generate" -> imageGenerate(args["prompt"]?.toString() ?: "")
    "memory_store" -> memoryStore(args["key"]?.toString() ?: "", args["content"]?.toString() ?: "", args["category"]?.toString() ?: "general")
    "memory_recall" -> memoryRecall(args["query"]?.toString() ?: "")
    "memory_forget" -> memoryForget(args["key"]?.toString() ?: "")
    else -> "Unknown tool: $name"
  }

  private suspend fun search(query: String, category: String, count: Int): String = withContext(Dispatchers.IO) {
    val n = count.coerceIn(1, 20)
    val req = Request.Builder().url("$searchUrl/search?q=${Uri.encode(query)}&format=json&categories=$category").build()
    val body = http.newCall(req).execute().use { it.body?.string() ?: "" }
    val json = JSONObject(body)
    val results = json.optJSONArray("results") ?: return@withContext "No results"
    if (category == "images") {
      buildString {
        appendLine("Image results for: \"$query\" (${minOf(results.length(), n)} results)\n")
        for (i in 0 until minOf(results.length(), n)) {
          val r = results.getJSONObject(i)
          val title = r.optString("title", "Untitled")
          val imgSrc = r.optString("img_src", "")
          val source = r.optString("source", r.optString("engine", ""))
          val pageUrl = r.optString("url", "")
          val w = r.optInt("img_width", 0)
          val h = r.optInt("img_height", 0)
          val dims = if (w > 0 && h > 0) "${w}x${h}" else ""
          appendLine("[${i + 1}] $title")
          if (dims.isNotEmpty()) appendLine("    Size: $dims")
          if (source.isNotEmpty()) appendLine("    Source: $source")
          appendLine("    Image: $imgSrc")
          if (pageUrl.isNotEmpty() && pageUrl != imgSrc) appendLine("    Page: $pageUrl")
          appendLine("    Markdown: ![${title}]($imgSrc)")
          appendLine()
        }
      }
    } else {
      buildString {
        appendLine("Search results for: \"$query\" (${minOf(results.length(), n)} results)\n")
        for (i in 0 until minOf(results.length(), n)) {
          val r = results.getJSONObject(i)
          val title = r.optString("title", "Untitled")
          val url = r.optString("url", "")
          val content = r.optString("content", "")
          val engines = r.optJSONArray("engines")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }.joinToString(", ")
          } ?: ""
          val date = r.optString("publishedDate", "").takeIf { it.isNotBlank() }
          val domain = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
          appendLine("[${i + 1}] $title")
          appendLine("    URL: $url")
          if (domain.isNotEmpty()) appendLine("    Domain: $domain")
          if (date != null) appendLine("    Published: $date")
          if (engines.isNotEmpty()) appendLine("    Engines: $engines")
          if (content.isNotEmpty()) appendLine("    Snippet: $content")
          appendLine()
        }
      }
    }
  }

  private suspend fun fetchUrl(url: String, mode: String): String = withContext(Dispatchers.IO) {
    if (mode == "image") return@withContext saveImage(url, "")
    val req = Request.Builder().url(url).header("User-Agent", "AIOPulse/1.0").build()
    val body = http.newCall(req).execute().use { it.body?.string()?.take(12000) ?: "" }
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
    return when (file.extension.lowercase()) {
      "docx" -> DocumentConverter.readDocx(file)
      "xlsx", "xls" -> DocumentConverter.readXlsx(file)
      "pdf" -> DocumentConverter.readPdf(file)
      "csv" -> DocumentConverter.readCsv(file)
      else -> file.readText().take(50000)
    }
  }

  private fun writeFile(path: String, content: String): String {
    val dir = DirectoryManager.workspace(conversationId)
    val file = File(dir, path.removePrefix("/"))
    file.parentFile?.mkdirs()
    file.writeText(content)
    val ext = file.extension.lowercase()
    val filePath = "file://${file.absolutePath}"
    return when (ext) {
      "png", "jpg", "jpeg", "gif", "webp" -> "Written ${file.name}\n![${file.name}]($filePath)"
      "svg" -> {
        try {
          val raster = com.xnet.pulse.feature.chat.engine.AttachmentProcessor(ctx).normalizeForVision(file.readBytes(), file.name)
          val rasterFile = File(file.parent, "${file.nameWithoutExtension}.jpg")
          rasterFile.writeBytes(raster)
          "Written ${file.name}\n![${file.name}](file://${rasterFile.absolutePath})"
        } catch (_: Exception) { "Written ${file.name}\n![${file.name}]($filePath)" }
      }
      "html" -> "Written ${file.name}\n\n```html\n${content.take(2000)}\n```\n\n📄 [Open ${file.name}]($filePath)"
      "md" -> "Written ${file.name}\n\n---\n${content.take(3000)}\n---"
      "txt" -> "Written ${file.name}\n\n${content.take(3000)}"
      else -> {
        val lang = when (ext) {
          "kt", "kts" -> "kotlin"; "py" -> "python"; "js" -> "javascript"; "ts" -> "typescript"
          "java" -> "java"; "go" -> "go"; "rs" -> "rust"; "sh" -> "bash"; "json" -> "json"
          "yaml", "yml" -> "yaml"; "xml" -> "xml"; "css" -> "css"; "sql" -> "sql"
          "latex", "tex" -> "latex"; "csv" -> "csv"
          else -> ext
        }
        "Written ${file.name}\n\n```$lang\n${content.take(3000)}\n```"
      }
    }
  }

  private fun editFile(path: String, old: String, new: String): String {
    val file = File(DirectoryManager.workspace(conversationId), path.removePrefix("/"))
    if (!file.exists()) return "File not found: $path"
    val content = file.readText()
    if (!content.contains(old)) return "Text not found in $path"
    val updated = content.replaceFirst(old, new)
    file.writeText(updated)
    return "Edited ${file.name} (${old.length} chars → ${new.length} chars)"
  }

  private fun exportDocument(path: String, format: String): String {
    val workspace = DirectoryManager.workspace(conversationId)
    val source = File(workspace, path.removePrefix("/"))
    if (!source.exists()) return "File not found: $path"
    val content = when (source.extension.lowercase()) {
      "docx" -> DocumentConverter.readDocx(source)
      "xlsx", "xls" -> DocumentConverter.readXlsx(source)
      "pdf" -> DocumentConverter.readPdf(source)
      "csv" -> DocumentConverter.readCsv(source)
      else -> source.readText()
    }
    val outName = "${source.nameWithoutExtension}.$format"
    val output = File(workspace, outName)
    try {
      when (format.lowercase()) {
        "docx" -> DocumentConverter.toDocx(content, output)
        "pdf" -> DocumentConverter.toPdf(content, output)
        "xlsx" -> DocumentConverter.toXlsx(content, output)
        "csv" -> DocumentConverter.toCsv(content, output)
        else -> return "Unsupported format: $format. Use: docx, pdf, xlsx, csv"
      }
    } catch (e: Exception) { return "Export failed: ${e.message}" }
    return "Exported to ${output.name} (${output.length() / 1024}KB)\n📄 file://${output.absolutePath}"
  }

  private suspend fun getLocation(): String = withContext(Dispatchers.IO) {
    try {
      val client = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(ctx)
      val loc = kotlinx.coroutines.suspendCancellableCoroutine<android.location.Location?> { cont ->
        val req = com.google.android.gms.location.CurrentLocationRequest.Builder()
          .setPriority(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY)
          .setMaxUpdateAgeMillis(10_000L)
          .build()
        client.getCurrentLocation(req, null)
          .addOnSuccessListener { cont.resume(it) { } }
          .addOnFailureListener { cont.resume(null) { } }
      } ?: return@withContext "Location unavailable — enable GPS or grant permission"

      val result = buildString {
        appendLine("Latitude: ${loc.latitude}")
        appendLine("Longitude: ${loc.longitude}")
        if (loc.hasAltitude()) appendLine("Altitude: ${"%.1f".format(loc.altitude)}m")
        if (loc.hasSpeed()) appendLine("Speed: ${"%.1f".format(loc.speed * 3.6)} km/h")
        appendLine("Accuracy: ${"%.1f".format(loc.accuracy)}m")
      }

      val address = try {
        val geocoder = android.location.Geocoder(ctx, java.util.Locale.US)
        val addrs = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
        addrs?.firstOrNull()?.getAddressLine(0)
      } catch (_: Exception) { null }

      if (address != null) "$result\nAddress: $address" else result
    } catch (e: SecurityException) { "Location permission not granted" }
  }

  private fun openIntent(uri: String): String {
    return try {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      ctx.startActivity(intent)
      "Opened: $uri"
    } catch (e: Exception) { "Failed to open: ${e.message}" }
  }
  fun fetchImageBytes(url: String): ByteArray {
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

  private suspend fun saveImage(url: String, filename: String): String = withContext(Dispatchers.IO) {
    val name = filename.ifBlank { "img_${System.currentTimeMillis()}.${url.substringAfterLast('.').take(4).ifBlank { "png" }}" }
    val file = File(DirectoryManager.generated(conversationId), name)
    try {
      val req = Request.Builder().url(url).build()
      http.newCall(req).execute().use { resp ->
        resp.body?.byteStream()?.use { input -> file.outputStream().use { input.copyTo(it) } }
      }
      "![${name}](file://${file.absolutePath})"
    } catch (e: Exception) { "Error saving image: ${e.message}" }
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

  private suspend fun memoryForget(key: String): String {
    if (key.isBlank()) return "Error: key required"
    dao.deleteMemory(key)
    return "Deleted memory: $key"
  }

  private fun runShell(command: String): String {
    return try {
      val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
      val output = process.inputStream.bufferedReader().readText()
      val error = process.errorStream.bufferedReader().readText()
      process.waitFor()
      (output + error).take(4000).ifBlank { "(no output)" }
    } catch (e: Exception) { "Error: ${e.message}" }
  }

  private fun deviceInfo(): String {
    val rt = Runtime.getRuntime()
    return "Model: ${android.os.Build.MODEL}\nAndroid: ${android.os.Build.VERSION.RELEASE}\nFree RAM: ${rt.freeMemory() / 1024 / 1024}MB\nTotal RAM: ${rt.totalMemory() / 1024 / 1024}MB"
  }

  private fun clipboardCopy(text: String): String {
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("pulse", text))
    return "Copied to clipboard"
  }

  private fun clipboardRead(): String {
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    return cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "(clipboard empty)"
  }

  private fun sendNotification(title: String, body: String): String {
    try {
      val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
      val channel = android.app.NotificationChannel("pulse_tools", "Pulse Tools", android.app.NotificationManager.IMPORTANCE_DEFAULT)
      nm.createNotificationChannel(channel)
      val n = android.app.Notification.Builder(ctx, "pulse_tools")
        .setContentTitle(title).setContentText(body)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .build()
      nm.notify(System.currentTimeMillis().toInt(), n)
      return "Notification sent: $title"
    } catch (e: Exception) { return "Error: ${e.message}" }
  }

}
