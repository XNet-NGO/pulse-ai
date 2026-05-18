package com.xnet.pulse.feature.chat.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
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
    ToolDef("read_calendar", "Read upcoming calendar events.", """{"type":"object","properties":{"days":{"type":"integer"}}}""", true),
    ToolDef("create_event", "Create a calendar event.", """{"type":"object","properties":{"title":{"type":"string"},"start_time":{"type":"string"},"end_time":{"type":"string"},"location":{"type":"string"},"description":{"type":"string"}},"required":["title"]}""", false),
    ToolDef("set_alarm", "Set an alarm.", """{"type":"object","properties":{"hour":{"type":"integer"},"minutes":{"type":"integer"},"message":{"type":"string"}},"required":["hour","minutes"]}""", false),
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
    "read_calendar" -> readCalendar((args["days"] as? Number)?.toInt() ?: 7)
    "create_event" -> createEvent(args)
    "set_alarm" -> setAlarm((args["hour"] as? Number)?.toInt() ?: 0, (args["minutes"] as? Number)?.toInt() ?: 0, args["message"]?.toString())
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
    val dir = resolveSandboxPath(path)
    if (!dir.exists()) return "Directory not found: $path"
    return dir.listFiles()?.joinToString("\n") { (if (it.isDirectory) "📁 " else "📄 ") + it.name } ?: "Empty"
  }

  private fun readFile(path: String): String {
    val file = resolveSandboxPath(path)
    if (!file.exists()) return "File not found: $path"
    return file.readText().take(50000)
  }

  private fun writeFile(path: String, content: String): String {
    val file = resolveSandboxPath(path)
    file.parentFile?.mkdirs()
    file.writeText(content)
    return "Written ${content.length} chars to $path"
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

  private fun readCalendar(days: Int): String {
    return try {
      val now = System.currentTimeMillis()
      val end = now + days * 86400000L
      val uri = android.provider.CalendarContract.Events.CONTENT_URI
      val projection = arrayOf("title", "dtstart", "dtend", "eventLocation")
      val selection = "dtstart >= ? AND dtstart <= ?"
      val cursor = ctx.contentResolver.query(uri, projection, selection, arrayOf("$now", "$end"), "dtstart ASC")
        ?: return "Calendar permission not granted"
      val events = buildString {
        while (cursor.moveToNext()) {
          val title = cursor.getString(0) ?: "Untitled"
          val start = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(cursor.getLong(1)))
          val loc = cursor.getString(3)?.takeIf { it.isNotBlank() }?.let { " @ $it" } ?: ""
          appendLine("• $title — $start$loc")
        }
      }
      cursor.close()
      events.ifBlank { "No events in the next $days days" }
    } catch (e: SecurityException) { "Calendar permission not granted" }
  }

  private fun createEvent(args: Map<String, Any?>): String {
    val intent = Intent(Intent.ACTION_INSERT).apply {
      data = CalendarContract.Events.CONTENT_URI
      putExtra(CalendarContract.Events.TITLE, args["title"]?.toString() ?: "")
      args["location"]?.toString()?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
      args["description"]?.toString()?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(intent)
    return "Calendar event creation opened"
  }

  private fun setAlarm(hour: Int, minutes: Int, message: String?): String {
    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
      putExtra(AlarmClock.EXTRA_HOUR, hour)
      putExtra(AlarmClock.EXTRA_MINUTES, minutes)
      message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
      putExtra(AlarmClock.EXTRA_SKIP_UI, true)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(intent)
    return "Alarm set for %02d:%02d".format(hour, minutes)
  }

  private suspend fun analyzeImage(url: String, question: String?): String = withContext(Dispatchers.IO) {
    val msgs = listOf(
      JSONObject().put("role", "user").put("content", JSONArray().apply {
        put(JSONObject().put("type", "text").put("text", question ?: "Describe this image in detail."))
        put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
      })
    )
    val sb = StringBuilder()
    pollinationsClient.stream(msgs, "openai").collect { ev ->
      if (ev is com.xnet.pulse.core.model.StreamEvent.Delta) sb.append(ev.text)
    }
    sb.toString().ifBlank { "Could not analyze image" }
  }

  private suspend fun imageGenerate(prompt: String): String {
    val url = pollinationsClient.imageUrl(prompt)
    // Save to sandbox
    val filename = "gen_${System.currentTimeMillis()}.png"
    val file = File(sandboxRoot, "images/$filename").also { it.parentFile?.mkdirs() }
    withContext(Dispatchers.IO) {
      val req = Request.Builder().url(url).build()
      http.newCall(req).execute().use { resp ->
        resp.body?.byteStream()?.use { input -> file.outputStream().use { input.copyTo(it) } }
      }
    }
    return "![Generated image](/images/$filename)\n\nSaved to /images/$filename"
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

  private fun resolveSandboxPath(path: String): File {
    val clean = path.removePrefix("/")
    return File(sandboxRoot, clean)
  }
}
