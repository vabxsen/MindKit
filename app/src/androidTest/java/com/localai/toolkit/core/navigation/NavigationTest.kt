package com.localai.toolkit.core.navigation

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.domain.model.ToolId
import org.junit.Test

/**
 * The routing contract the rest of the app depends on.
 *
 * Runs as an instrumentation test because [ToolCatalog] holds Compose [ImageVector]s and
 * string resource ids, which belong to the Android artifact rather than the JVM one.
 */
class NavigationTest {

    @Test
    fun theDeveloperToolRouteCarriesItsToolName() {
        assertThat(Destination.developerTool("JSON_FORMATTER"))
            .isEqualTo("tool/developer/JSON_FORMATTER")
    }

    @Test
    fun theHistoryDetailRouteCarriesItsId() {
        assertThat(Destination.historyDetail(42)).isEqualTo("history/42")
    }

    @Test
    fun onlyTheThreeTabsAreTopLevel() {
        // The bottom bar is hidden everywhere else, so a tool screen gets the full height.
        assertThat(Destination.topLevelRoutes).containsExactly(
            Destination.HOME,
            Destination.HISTORY,
            Destination.SETTINGS,
        )
        assertThat(Destination.topLevelRoutes).doesNotContain(Destination.ASK)
        assertThat(Destination.topLevelRoutes).doesNotContain(Destination.OCR)
    }

    @Test
    fun everyToolInTheCatalogHasADistinctRoute() {
        val routes = ToolCatalog.entries.map { it.route }

        assertThat(routes).containsNoDuplicates()
        assertThat(routes).hasSize(ToolId.entries.size)
    }

    @Test
    fun everyToolIdResolvesToACatalogEntry() {
        // ToolCatalog[id] throws when an entry is missing, which would crash Home.
        ToolId.entries.forEach { id ->
            assertThat(ToolCatalog[id].id).isEqualTo(id)
        }
    }

    @Test
    fun theToolHandoffDeliversOnlyToTheAddressedTool() {
        val handoff = ToolHandoff()
        handoff.send(HandoffPayload(target = ToolId.SUMMARIZE, text = "hello"))

        // A tool that was not the target must not pick up someone else's payload.
        assertThat(handoff.consume(ToolId.REWRITE)).isNull()
        assertThat(handoff.consume(ToolId.SUMMARIZE)).isNotNull()
    }

    @Test
    fun theToolHandoffDeliversExactlyOnce() {
        val handoff = ToolHandoff()
        handoff.send(HandoffPayload(target = ToolId.ASK, text = "hello"))

        assertThat(handoff.consume(ToolId.ASK)).isNotNull()
        // Returning to the screen, or a configuration change, must not replay it.
        assertThat(handoff.consume(ToolId.ASK)).isNull()
    }

    @Test
    fun clearingTheHandoffDropsAPendingPayload() {
        val handoff = ToolHandoff()
        handoff.send(HandoffPayload(target = ToolId.OCR, text = "hello"))

        handoff.clear()

        assertThat(handoff.consume(ToolId.OCR)).isNull()
    }
}
