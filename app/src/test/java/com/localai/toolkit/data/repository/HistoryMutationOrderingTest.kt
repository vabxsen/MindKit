package com.localai.toolkit.data.repository

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.data.local.db.HistoryDao
import com.localai.toolkit.data.local.db.HistoryEntity
import com.localai.toolkit.domain.model.AppSettings
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
import com.localai.toolkit.domain.repository.SettingsRepository
import com.localai.toolkit.testing.MemorySettings
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryMutationOrderingTest {
    private val item = HistoryItem(type = HistoryType.TRANSLATION, title = "Result",
        inputPreview = "Hello", output = "Hola", createdAtEpochMillis = 1L)

    @Test fun `clear waits for an already started save and cannot be undone by that save`() = runTest {
        val preferenceRead = CompletableDeferred<AppSettings>()
        val settings = object : SettingsRepository by MemorySettings() {
            override val settings = flow { emit(preferenceRead.await()) }
        }
        val dao = RecordingDao()
        val repository = HistoryRepositoryImpl(dao, settings, StandardTestDispatcher(testScheduler))
        val save = async { repository.save(item) }
        runCurrent()
        val clear = async { repository.deleteAll() }
        runCurrent()
        assertThat(clear.isCompleted).isFalse()
        preferenceRead.complete(AppSettings(saveHistory = true))
        save.await()
        clear.await()
        assertThat(repository.count()).isEqualTo(0)
        assertThat(dao.operations).containsExactly("save", "clear").inOrder()
        repository.save(item)
        assertThat(repository.count()).isEqualTo(1)
    }

    @Test fun `cancelling a waiting save releases clear without inserting anything`() = runTest {
        val preferenceRead = CompletableDeferred<AppSettings>()
        val settings = object : SettingsRepository by MemorySettings() {
            override val settings = flow { emit(preferenceRead.await()) }
        }
        val dao = RecordingDao()
        val repository = HistoryRepositoryImpl(dao, settings, StandardTestDispatcher(testScheduler))
        val save = async { repository.save(item) }
        runCurrent()
        val clear = async { repository.deleteAll() }
        runCurrent()
        save.cancel()
        clear.await()
        assertThat(dao.operations).containsExactly("clear")
        assertThat(repository.count()).isEqualTo(0)
    }

    @Test fun `failed clear releases the mutation lock for save delete and retry`() = runTest {
        val dao = RecordingDao()
        val repository = HistoryRepositoryImpl(dao, MemorySettings(), StandardTestDispatcher(testScheduler))
        dao.failClear = true
        val failure = runCatching { repository.deleteAll() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IOException::class.java)
        val id = repository.save(item)!!
        repository.delete(id)
        assertThat(repository.count()).isEqualTo(0)
        repository.save(item)
        dao.failClear = false
        repository.deleteAll()
        assertThat(repository.count()).isEqualTo(0)
    }

    private class RecordingDao : HistoryDao {
        private val rows = mutableListOf<HistoryEntity>()
        private var nextId = 1L
        val operations = mutableListOf<String>()
        var failClear = false
        override fun observe(query: String, types: List<String>, typeFilterActive: Int) = flowOf(rows.toList())
        override fun observeById(id: Long) = flowOf(rows.find { it.id == id })
        override suspend fun insert(entity: HistoryEntity): Long {
            operations += "save"
            val id = nextId++
            rows += entity.copy(id = id)
            return id
        }
        override suspend fun delete(id: Long) { rows.removeAll { it.id == id } }
        override suspend fun deleteAll() {
            if (failClear) throw IOException("clear failed")
            operations += "clear"
            rows.clear()
        }
        override suspend fun count() = rows.size
    }
}
