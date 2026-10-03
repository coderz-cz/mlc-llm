package ai.mlc.mlcchat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single persisted chat message, belonging to one [ChatSessionEntity]
 * (one conversation/topic). [modelId] is kept alongside [sessionId]
 * (denormalized) purely so model-wide cleanup (deleting a model) doesn't
 * need a join.
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val modelId: String,
    // Stores MessageRole.name ("User" / "Assistant"), see ai.mlc.mlcchat.MessageRole.
    val role: String,
    val text: String,
    // Monotonically increasing per session, used to restore message order
    // (SQLite/Room does not otherwise guarantee row return order).
    val orderIndex: Long,
)
