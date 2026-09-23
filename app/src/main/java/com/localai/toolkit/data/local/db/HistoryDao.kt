package com.localai.toolkit.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    /**
     * Newest first, optionally narrowed by free text and by type.
     *
     * [typeFilterActive] lets one query serve both "all types" and "these types": when it
     * is 0 the IN clause is skipped entirely, which avoids a second query and avoids the
     * empty-IN-list pitfall.
     */
    @Query(
        """
        SELECT * FROM history
        WHERE (:query = ''
               OR title LIKE '%' || :query || '%'
               OR input_preview LIKE '%' || :query || '%'
               OR output LIKE '%' || :query || '%')
          AND (:typeFilterActive = 0 OR type IN (:types))
        ORDER BY created_at DESC
        """,
    )
    fun observe(query: String, types: List<String>, typeFilterActive: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE id = :id")
    fun observeById(id: Long): Flow<HistoryEntity?>

    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int
}
