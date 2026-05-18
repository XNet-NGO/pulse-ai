package com.xnet.pulse.feature.chat.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
  @PrimaryKey val id: String,
  val title: String = "New Chat",
  val agentName: String = "default",
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
  tableName = "messages",
  foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)],
  indices = [Index("conversationId")],
)
data class MessageEntity(
  @PrimaryKey val id: String,
  val conversationId: String,
  val role: String,
  val content: String,
  val reasoning: String = "",
  val imagePaths: String = "",
  val timestamp: Long = System.currentTimeMillis(),
  val status: String = "sent",
)

@Entity(tableName = "memories")
data class MemoryEntity(
  @PrimaryKey val key: String,
  val content: String,
  val category: String = "general",
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "settings_kv")
data class SettingsKvEntity(
  @PrimaryKey val key: String,
  val value: String,
)

@Dao
interface ChatDao {
  // Conversations
  @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
  fun getConversations(): Flow<List<ConversationEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertConversation(c: ConversationEntity)

  @Query("UPDATE conversations SET title = :title, updatedAt = :time WHERE id = :id")
  suspend fun updateConversation(id: String, title: String, time: Long = System.currentTimeMillis())

  @Query("UPDATE conversations SET updatedAt = :time WHERE id = :id")
  suspend fun touchConversation(id: String, time: Long = System.currentTimeMillis())

  @Query("DELETE FROM conversations WHERE id = :id")
  suspend fun deleteConversation(id: String)

  // Messages
  @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY timestamp ASC")
  suspend fun getMessages(convId: String): List<MessageEntity>

  @Query("SELECT * FROM messages WHERE conversationId = :convId AND status = 'queued'")
  suspend fun getQueuedMessages(convId: String): List<MessageEntity>

  @Query("SELECT * FROM messages WHERE status = 'queued'")
  suspend fun getAllQueued(): List<MessageEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertMessage(m: MessageEntity)

  @Query("UPDATE messages SET content = :content, status = 'sent' WHERE id = :id")
  suspend fun updateMessage(id: String, content: String)

  @Query("UPDATE messages SET status = :status WHERE id = :id")
  suspend fun updateStatus(id: String, status: String)

  @Query("DELETE FROM messages WHERE conversationId = :convId AND timestamp >= :after")
  suspend fun deleteMessagesAfter(convId: String, after: Long)

  // Memories
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertMemory(m: MemoryEntity)

  @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
  suspend fun getAllMemories(): List<MemoryEntity>

  @Query("SELECT * FROM memories WHERE key LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%'")
  suspend fun searchMemories(q: String): List<MemoryEntity>

  @Query("DELETE FROM memories WHERE key = :key")
  suspend fun deleteMemory(key: String)

  // Settings
  @Query("SELECT value FROM settings_kv WHERE key = :key")
  suspend fun getSetting(key: String): String?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertSetting(s: SettingsKvEntity)
}

@Database(
  entities = [ConversationEntity::class, MessageEntity::class, MemoryEntity::class, SettingsKvEntity::class],
  version = 1,
  exportSchema = false,
)
abstract class PulseDatabase : RoomDatabase() {
  abstract fun chatDao(): ChatDao
}
