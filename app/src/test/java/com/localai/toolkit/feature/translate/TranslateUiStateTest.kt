package com.localai.toolkit.feature.translate

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.mlkit.TranslationLanguage
import org.junit.Test

/**
 * The rules that decide whether Translate can actually run.
 *
 * Getting these wrong means either a disabled button the user cannot explain, or a
 * translate attempt that fails because a language pack is missing.
 */
class TranslateUiStateTest {

    private val languages = listOf(
        TranslationLanguage("en", "English"),
        TranslationLanguage("es", "Spanish"),
        TranslationLanguage("de", "German"),
    )

    @Test
    fun `both languages are reported missing when nothing is downloaded`() {
        val state = state(source = "en", target = "es", downloaded = emptySet())

        assertThat(state.missingModels).containsExactly("en", "es")
    }

    @Test
    fun `only the undownloaded language is reported missing`() {
        val state = state(source = "en", target = "es", downloaded = setOf("en"))

        assertThat(state.missingModels).containsExactly("es")
    }

    @Test
    fun `nothing is missing once both packs are present`() {
        val state = state(source = "en", target = "es", downloaded = setOf("en", "es"))

        assertThat(state.missingModels).isEmpty()
    }

    @Test
    fun `a repeated language is only reported once`() {
        // distinct() guards against telling the user to download the same pack twice.
        val state = state(source = "en", target = "en", downloaded = emptySet())

        assertThat(state.missingModels).containsExactly("en")
    }

    @Test
    fun `translating is blocked when the two languages are the same`() {
        val state = state(source = "en", target = "en", downloaded = setOf("en"))
            .copy(input = "hello")

        assertThat(state.sameLanguageSelected).isTrue()
        assertThat(state.canTranslate).isFalse()
    }

    @Test
    fun `translating is blocked on empty input`() {
        val state = state(source = "en", target = "es", downloaded = setOf("en", "es"))

        assertThat(state.canTranslate).isFalse()
    }

    @Test
    fun `translating is blocked while a translation is already running`() {
        val state = state(source = "en", target = "es", downloaded = setOf("en", "es"))
            .copy(input = "hello", isTranslating = true)

        assertThat(state.canTranslate).isFalse()
    }

    @Test
    fun `translating is allowed with text and two different languages`() {
        val state = state(source = "en", target = "es", downloaded = setOf("en", "es"))
            .copy(input = "hello")

        assertThat(state.canTranslate).isTrue()
    }

    @Test
    fun `display name falls back to the uppercased code for an unknown language`() {
        val state = state(source = "en", target = "es", downloaded = emptySet())

        assertThat(state.displayNameOf("en")).isEqualTo("English")
        assertThat(state.displayNameOf("zz")).isEqualTo("ZZ")
    }

    private fun state(source: String, target: String, downloaded: Set<String>) = TranslateUiState(
        languages = languages,
        downloadedLanguages = downloaded,
        sourceCode = source,
        targetCode = target,
    )
}
