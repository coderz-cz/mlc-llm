package ai.mlc.mlcchat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY orderIndex ASC")
    fun getMessagesForSession(sessionId: Long): List<ChatMessageEntity>

    @Insert
    fun insert(message: ChatMessageEntity): Long

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM chat_messages WHERE sessionId = :sessionId")
    fun getMaxOrderIndex(sessionId: Long): Long

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    fun clearForSession(sessionId: Long)

    @Query("DELETE FROM chat_messages WHERE modelId = :modelId")
    fun clearForModel(modelId: String)
}
