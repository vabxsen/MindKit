package com.localai.toolkit.data.repository

import com.localai.toolkit.data.local.db.HistoryDao
import com.localai.toolkit.data.local.db.toDomain
import com.localai.toolkit.data.local.db.toEntity
import com.localai.toolkit.di.IoDispatcher
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class HistoryRepositoryImpl @Inject constructor(
    private val dao: HistoryDao,
    private val settingsRepository: SettingsRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : HistoryRepository {
    // Acquire before dispatch/read suspension: a pending Save must not undo a later Clear.
    private val mutationMutex = Mutex()

    override fun observe(query: String, types: Set<HistoryType>): Flow<List<HistoryItem>> =
        dao.observe(
            query = query.trim(),
            types = types.map { it.name },
            typeFilterActive = if (types.isEmpty()) 0 else 1,
        )
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(ioDispatcher)

    override fun observeById(id: Long): Flow<HistoryItem?> =
        dao.observeById(id).map { it?.toDomain() }.flowOn(ioDispatcher)

    override suspend fun save(item: HistoryItem): Long? = mutationMutex.withLock {
        withContext(ioDispatcher) {
            // The preference is the gate: with history off, results are shown but never
            // written to disk.
            if (!settingsRepository.settings.first().saveHistory) return@withContext null
            dao.insert(item.toEntity())
        }
    }

    override suspend fun delete(id: Long) = mutationMutex.withLock {
        withContext(ioDispatcher) { dao.delete(id) }
    }

    override suspend fun deleteAll() = mutationMutex.withLock {
        withContext(ioDispatcher) { dao.deleteAll() }
    }

    override suspend fun count(): Int = withContext(ioDispatcher) { dao.count() }
}
