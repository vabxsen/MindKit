package com.localai.toolkit.testing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.localai.toolkit.domain.model.*
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.domain.repository.SettingsRepository
import com.localai.toolkit.domain.usecase.SettingsActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
    fun settingsActions(settings: SettingsRepository, history: HistoryRepository = MemoryHistory()) =
        SettingsActions(settings, history, applicationScope)
    fun <T : ViewModel> own(model: T): T = model.also { store.put("model-${models++}", it) }
    fun clearViewModels() = store.clear()
    private var models = 0
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) {
        store.clear()
        applicationScope.cancel()
        Dispatchers.resetMain()
    }
}

class MemorySettings : SettingsRepository {
    override val settings = MutableStateFlow(AppSettings())
    override suspend fun setThemeMode(mode: ThemeMode) { settings.value = settings.value.copy(themeMode = mode) }
    override suspend fun setDynamicColor(enabled: Boolean) { settings.value = settings.value.copy(dynamicColor = enabled) }
    override suspend fun setSaveHistory(enabled: Boolean) { settings.value = settings.value.copy(saveHistory = enabled) }
    override suspend fun setOnboardingCompleted(completed: Boolean) { settings.value = settings.value.copy(onboardingCompleted = completed) }
    override suspend fun setVerboseErrors(enabled: Boolean) { settings.value = settings.value.copy(verboseErrors = enabled) }
    override suspend fun setTranslateLanguages(source: String?, target: String?) {
        settings.value = settings.value.copy(lastTranslateSource = source, lastTranslateTarget = target)
    }
    override suspend fun clear() { settings.value = AppSettings() }
}

class MemoryHistory : HistoryRepository {
    val items = MutableStateFlow<List<HistoryItem>>(emptyList())
    override fun observe(query: String, types: Set<HistoryType>) = items.map { rows ->
        rows.filter { (types.isEmpty() || it.type in types) &&
            listOf(it.title, it.inputPreview, it.output).any { value -> value.contains(query, true) } }
    }
    override fun observeById(id: Long) = items.map { rows -> rows.find { it.id == id } }
    override suspend fun save(item: HistoryItem): Long {
        val id = (items.value.maxOfOrNull { it.id } ?: 0) + 1
        items.value += item.copy(id = id)
        return id
    }
    override suspend fun delete(id: Long) { items.value = items.value.filterNot { it.id == id } }
    override suspend fun deleteAll() { items.value = emptyList() }
    override suspend fun count() = items.value.size
}

class TestCapabilities : com.localai.toolkit.ai.capability.DeviceAiCapabilityManager {
    override val snapshot = MutableStateFlow(DeviceAiSnapshot())
    val refreshedTasks = mutableListOf<AiTask>()
    override suspend fun refresh(force: Boolean) = Unit
    override suspend fun refresh(task: AiTask) { refreshedTasks += task }
}
