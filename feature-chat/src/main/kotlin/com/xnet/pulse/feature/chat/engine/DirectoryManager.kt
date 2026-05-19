package com.xnet.pulse.feature.chat.engine

import android.content.Context
import java.io.File

object DirectoryManager {
  private lateinit var root: File

  fun init(ctx: Context) {
    root = File(ctx.filesDir, "pulse")
    root.mkdirs()
    File(root, "cache").mkdirs()
    File(root, "memory").mkdirs()
    // Clear volatile cache on start
    File(root, "cache").listFiles()?.forEach { it.deleteRecursively() }
  }

  fun generated(convId: String) = File(root, "generated/$convId").also { it.mkdirs() }
  fun uploads(convId: String) = File(root, "uploads/$convId").also { it.mkdirs() }
  fun workspace(convId: String) = File(root, "workspace/$convId").also { it.mkdirs() }
  fun cache() = File(root, "cache").also { it.mkdirs() }
  fun memory() = File(root, "memory")

  fun deleteConversation(convId: String) {
    File(root, "generated/$convId").deleteRecursively()
    File(root, "uploads/$convId").deleteRecursively()
    File(root, "workspace/$convId").deleteRecursively()
  }
}
