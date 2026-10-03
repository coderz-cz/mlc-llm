package ai.mlc.mlcchat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single conversation ("topic") the user is having with a given model.
 * A model can have many sessions; [ChatMessageEntity] rows belong to exactly
 * one session via [ChatMessageEntity.sessionId].
 */
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelId: String,
    // Auto-set from the session's first user message; see ChatMessageDao.setInitialTitleIfDefault.
    val title: String,
    val createdAt: Long,
    val lastUpdatedAt: Long,
)
