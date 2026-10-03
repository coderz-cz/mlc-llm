package ai.mlc.mlcchat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

const val DEFAULT_SESSION_TITLE = "New Chat"

@Dao
interface ChatSessionDao {
    @Query("SELECT * FROM chat_sessions WHERE modelId = :modelId ORDER BY lastUpdatedAt DESC")
    fun getSessionsForModel(modelId: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    fun getSession(sessionId: Long): ChatSessionEntity?

    @Insert
    fun insert(session: ChatSessionEntity): Long

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    fun deleteSession(sessionId: Long)

    @Query("DELETE FROM chat_sessions WHERE modelId = :modelId")
    fun clearSessionsForModel(modelId: String)

    @Query("UPDATE chat_sessions SET lastUpdatedAt = :timestamp WHERE id = :sessionId")
    fun touchSession(sessionId: Long, timestamp: Long)

    // Only fires the first time (title still the default placeholder), so the
    // session gets named after the user's opening message exactly once.
    @Query("UPDATE chat_sessions SET title = :title WHERE id = :sessionId AND title = '$DEFAULT_SESSION_TITLE'")
    fun setInitialTitleIfDefault(sessionId: Long, title: String)
}
