package com.localai.toolkit.domain.repository

import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
import kotlinx.coroutines.flow.Flow

/** Local result history. Room backed; nothing is synced anywhere. */
interface HistoryRepository {

    /**
     * Observes stored results, newest first.
     *
     * @param query free-text match over title, input preview and output. Blank matches all.
     * @param types restrict to these types, or all types when empty.
     */
    fun observe(query: String = "", types: Set<HistoryType> = emptySet()): Flow<List<HistoryItem>>

    fun observeById(id: Long): Flow<HistoryItem?>

    /**
     * Stores a result if the user has history enabled.
     *
     * @return the new row id, or null when history is disabled and nothing was written.
     */
    suspend fun save(item: HistoryItem): Long?

    suspend fun delete(id: Long)

    suspend fun deleteAll()

    suspend fun count(): Int
}
