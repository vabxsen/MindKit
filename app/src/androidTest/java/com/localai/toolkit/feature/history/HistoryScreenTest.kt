package com.localai.toolkit.feature.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onLast
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
import org.junit.Rule
import org.junit.Test

/**
 * History, driven through its stateless content composable.
 *
 * The distinction that matters here is "nothing stored yet" versus "filtered down to
 * nothing": showing the first-run empty state to someone whose search simply missed would
 * suggest their data had been lost.
 */
class HistoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleItems = listOf(
        HistoryItem(
            id = 1,
            type = HistoryType.SUMMARY,
            title = "Quarterly planning notes",
            inputPreview = "The team agreed to...",
            output = "- Ship the beta in March",
            createdAtEpochMillis = 1_000L,
        ),
        HistoryItem(
            id = 2,
            type = HistoryType.OCR,
            title = "Receipt",
            inputPreview = "image",
            output = "TOTAL 18.40",
            createdAtEpochMillis = 2_000L,
        ),
    )

    @Test
    fun anEmptyStoreShowsTheFirstRunEmptyState() {
        setContent(HistoryUiState(isLoading = false))

        composeRule.onNodeWithText("Nothing here yet.").assertIsDisplayed()
    }

    @Test
    fun storedResultsAreListed() {
        setContent(HistoryUiState(isLoading = false, items = sampleItems))

        composeRule.onNodeWithText("Quarterly planning notes").assertIsDisplayed()
        composeRule.onNodeWithText("Receipt").assertIsDisplayed()
    }

    @Test
    fun aSearchThatMatchesNothingIsDistinguishedFromAnEmptyStore() {
        setContent(
            HistoryUiState(isLoading = false, items = emptyList(), query = "nothing matches"),
        )

        composeRule.onNodeWithText("No matching results").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing here yet.").assertDoesNotExist()
    }

    @Test
    fun clearingFiltersIsOfferedWhenAFilterEmptiedTheList() {
        var cleared = false
        setContent(
            state = HistoryUiState(isLoading = false, query = "zzz"),
            onClearFilters = { cleared = true },
        )

        composeRule.onNodeWithText("Clear").performClick()

        assertThat(cleared).isTrue()
    }

    @Test
    fun tappingAResultOpensIt() {
        var openedId: Long? = null
        setContent(
            state = HistoryUiState(isLoading = false, items = sampleItems),
            onOpenItem = { id -> openedId = id },
        )

        composeRule.onNodeWithText("Receipt").performClick()

        assertThat(openedId).isEqualTo(2L)
    }

    @Test
    fun deleteAllAsksForConfirmationBeforeDeleting() {
        var deletedAll = false
        setContent(
            state = HistoryUiState(isLoading = false, items = sampleItems),
            onDeleteAll = { deletedAll = true },
        )

        composeRule.onNodeWithText("Delete all").performClick()

        // The dialog is up; nothing has been deleted yet.
        assertThat(deletedAll).isFalse()
        composeRule.onNodeWithText("Delete all history?").assertIsDisplayed()
    }

    @Test
    fun confirmingTheDialogDeletesEverything() {
        var deletedAll = false
        setContent(
            state = HistoryUiState(isLoading = false, items = sampleItems),
            onDeleteAll = { deletedAll = true },
        )

        composeRule.onNodeWithText("Delete all").performClick()
        // The confirm button carries the same label; the last match is the dialog's.
        composeRule.onAllNodesWithTextDeleteAll().onLast().performClick()

        assertThat(deletedAll).isTrue()
    }

    @Test
    fun typeFilterChipsAreOffered() {
        setContent(HistoryUiState(isLoading = false, items = sampleItems))

        composeRule.onNodeWithText("Summary").assertIsDisplayed()
    }

    private fun setContent(
        state: HistoryUiState,
        onClearFilters: () -> Unit = {},
        onDeleteAll: () -> Unit = {},
        onOpenItem: (Long) -> Unit = {},
    ) {
        composeRule.setContent {
            LocalAiTheme {
                HistoryContent(
                    state = state,
                    queryText = state.query,
                    onQueryChange = {},
                    onToggleType = {},
                    onClearFilters = onClearFilters,
                    onDeleteAll = onDeleteAll,
                    onDelete = {},
                    onOpenItem = onOpenItem,
                )
            }
        }
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextDeleteAll() =
    onAllNodes(androidx.compose.ui.test.hasText("Delete all"))
