package com.localai.toolkit.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.data.local.db.HistoryDao
import com.localai.toolkit.data.local.db.LocalAiDatabase
import com.localai.toolkit.data.repository.HistoryRepositoryImpl
import com.localai.toolkit.domain.model.AppSettings
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
import com.localai.toolkit.domain.model.ThemeMode
import com.localai.toolkit.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * History persistence, exercised against a real in-memory Room database.
 *
 * Uses the real DAO rather than a mock so the SQL - including the search and type filter
 * - is actually covered.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryRepositoryTest {

    private lateinit var database: LocalAiDatabase
    private lateinit var dao: HistoryDao
    private lateinit var settings: FakeSettingsRepository
    private lateinit var repository: HistoryRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LocalAiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.historyDao()
        settings = FakeSettingsRepository()
        // Unconfined rather than a TestDispatcher: runTest owns the test scheduler, and a
        // second TestDispatcher created here would be a different scheduler, which
        // kotlinx-coroutines rejects. The repository only needs "not the main thread".
        repository = HistoryRepositoryImpl(dao, settings, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `save stores an item and observe returns it`() = runTest {
        val id = repository.save(item(title = "Meeting notes"))

        assertThat(id).isNotNull()
        val stored = repository.observe().first()
        assertThat(stored).hasSize(1)
        assertThat(stored.single().title).isEqualTo("Meeting notes")
    }

    @Test
    fun `save is skipped entirely when history is turned off`() = runTest {
        settings.state.value = AppSettings(saveHistory = false)

        val id = repository.save(item())

        assertThat(id).isNull()
        assertThat(repository.count()).isEqualTo(0)
    }

    @Test
    fun `results come back newest first`() = runTest {
        repository.save(item(title = "older", createdAt = 1_000L))
        repository.save(item(title = "newer", createdAt = 2_000L))

        val stored = repository.observe().first()

        assertThat(stored.map { it.title }).containsExactly("newer", "older").inOrder()
    }

    @Test
    fun `search matches title input and output`() = runTest {
        repository.save(item(title = "Receipt", inputPreview = "photo", output = "TOTAL 18.40"))
        repository.save(item(title = "Notes", inputPreview = "standup", output = "ship it"))

        assertThat(repository.observe(query = "receipt").first()).hasSize(1)
        assertThat(repository.observe(query = "standup").first()).hasSize(1)
        assertThat(repository.observe(query = "18.40").first()).hasSize(1)
        assertThat(repository.observe(query = "nothing here").first()).isEmpty()
    }

    @Test
    fun `an empty type filter returns every type`() = runTest {
        repository.save(item(type = HistoryType.OCR))
        repository.save(item(type = HistoryType.SUMMARY))

        val stored = repository.observe(types = emptySet()).first()

        assertThat(stored).hasSize(2)
    }

    @Test
    fun `type filter narrows to the selected types`() = runTest {
        repository.save(item(type = HistoryType.OCR))
        repository.save(item(type = HistoryType.SUMMARY))
        repository.save(item(type = HistoryType.TRANSLATION))

        val stored = repository.observe(
            types = setOf(HistoryType.OCR, HistoryType.TRANSLATION),
        ).first()

        assertThat(stored.map { it.type })
            .containsExactly(HistoryType.OCR, HistoryType.TRANSLATION)
    }

    @Test
    fun `delete removes a single row and deleteAll empties the table`() = runTest {
        val first = repository.save(item(title = "one"))!!
        repository.save(item(title = "two"))

        repository.delete(first)
        assertThat(repository.count()).isEqualTo(1)

        repository.deleteAll()
        assertThat(repository.count()).isEqualTo(0)
    }

    @Test
    fun `the history type survives a persistence round trip`() = runTest {
        // Stored by name, so reordering the enum must not re-label existing rows.
        repository.save(item(type = HistoryType.IMAGE_DESCRIPTION))

        val stored = repository.observe().first().single()

        assertThat(stored.type).isEqualTo(HistoryType.IMAGE_DESCRIPTION)
    }

    private fun item(
        type: HistoryType = HistoryType.ASK,
        title: String = "title",
        inputPreview: String = "input",
        output: String = "output",
        createdAt: Long = 1_000L,
    ) = HistoryItem(
        type = type,
        title = title,
        inputPreview = inputPreview,
        output = output,
        createdAtEpochMillis = createdAt,
    )
}

/** Minimal in-memory settings, so the repository test does not need DataStore. */
class FakeSettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {

    val state = MutableStateFlow(initial)

    override val settings: Flow<AppSettings> = state

    override suspend fun setThemeMode(mode: ThemeMode) {
        state.value = state.value.copy(themeMode = mode)
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        state.value = state.value.copy(dynamicColor = enabled)
    }

    override suspend fun setSaveHistory(enabled: Boolean) {
        state.value = state.value.copy(saveHistory = enabled)
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        state.value = state.value.copy(onboardingCompleted = completed)
    }

    override suspend fun setVerboseErrors(enabled: Boolean) {
        state.value = state.value.copy(verboseErrors = enabled)
    }

    override suspend fun setTranslateLanguages(source: String?, target: String?) {
        state.value = state.value.copy(lastTranslateSource = source, lastTranslateTarget = target)
    }

    override suspend fun clear() {
        state.value = AppSettings()
    }
}
