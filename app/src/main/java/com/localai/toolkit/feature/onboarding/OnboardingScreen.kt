package com.localai.toolkit.feature.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localai.toolkit.R
import com.localai.toolkit.core.designsystem.component.LoadingState
import com.localai.toolkit.core.designsystem.component.ErrorCard
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.designsystem.theme.Spacing
import com.localai.toolkit.core.designsystem.theme.FieldNotesCanvas
import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.domain.model.DeviceAiSnapshot
import com.localai.toolkit.feature.capability.CapabilityRow
import kotlinx.coroutines.launch

private data class OnboardingPage(val titleRes: Int, val bodyRes: Int)

private val pages = listOf(
    OnboardingPage(R.string.onboarding_page1_title, R.string.onboarding_page1_body),
    OnboardingPage(R.string.onboarding_page2_title, R.string.onboarding_page2_body),
    OnboardingPage(R.string.onboarding_page3_title, R.string.onboarding_page3_body),
)

/**
 * First-run flow.
 *
 * Three short pages, then an explicit device check. No account, no sign-in and nothing
 * mandatory: Skip is available on every page and completes onboarding just as
 * "Get started" does.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val isCompleting by viewModel.isCompleting.collectAsStateWithLifecycle()
    val finishFailed by viewModel.finishFailed.collectAsStateWithLifecycle()

    OnboardingContent(
        stage = stage,
        snapshot = snapshot,
        isCompleting = isCompleting,
        finishFailed = finishFailed,
        onCheckDevice = viewModel::checkDevice,
        onFinish = { viewModel.complete(onFinished) },
        modifier = modifier,
    )
}

@Composable
internal fun OnboardingContent(
    stage: OnboardingStage,
    snapshot: DeviceAiSnapshot,
    onCheckDevice: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    isCompleting: Boolean = false,
    finishFailed: Boolean = false,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (finishFailed) {
                ErrorCard(
                    message = stringResource(R.string.onboarding_finish_failed),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onFinish,
                    modifier = Modifier.padding(Spacing.M),
                )
            }
        },
    ) { padding ->
        when (stage) {
            OnboardingStage.INTRO -> IntroPages(
                onCheckDevice = onCheckDevice,
                onSkip = onFinish,
                enabled = !isCompleting,
                modifier = Modifier.padding(padding),
            )

            OnboardingStage.CHECKING -> Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingState(label = stringResource(R.string.loading_checking_availability))
                    TextButton(onClick = onFinish, enabled = !isCompleting) {
                        Text(stringResource(R.string.action_skip))
                    }
                }
            }

            OnboardingStage.RESULTS -> ResultsPage(
                snapshot = snapshot,
                onFinish = onFinish,
                enabled = !isCompleting,
                modifier = Modifier.padding(padding),
            )

            OnboardingStage.ERROR -> Column(
                modifier = Modifier.padding(padding).padding(Spacing.M),
                verticalArrangement = Arrangement.spacedBy(Spacing.M),
            ) {
                ErrorCard(
                    message = stringResource(R.string.gate_check_failed),
                    actionLabel = stringResource(R.string.action_retry).takeIf { !isCompleting },
                    onAction = onCheckDevice.takeIf { !isCompleting },
                )
                TextButton(onClick = onFinish, enabled = !isCompleting) {
                    Text(stringResource(R.string.action_skip))
                }
            }
        }
    }
}

@Composable
private fun IntroPages(
    onCheckDevice: () -> Unit,
    onSkip: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == pages.lastIndex

    Column(modifier = modifier.fillMaxSize().background(FieldNotesCanvas)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.S, vertical = Spacing.S),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onSkip, enabled = enabled) { Text(stringResource(R.string.action_skip)) }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            val item = pages[page]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.XXXL),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.field_notes_hero),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(190.dp),
                )
                Text(
                    text = stringResource(item.titleRes),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(top = Spacing.XL).semantics { heading() },
                )
                Text(
                    text = stringResource(item.bodyRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(top = Spacing.M),
                )
            }
        }

        val indicatorDescription = stringResource(
            R.string.onboarding_page_indicator,
            pagerState.currentPage + 1,
            pages.size,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.L)
                .clearAndSetSemantics { contentDescription = indicatorDescription },
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(pages.size) { index ->
                val selected = index == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .padding(horizontal = Spacing.XS)
                        .size(if (selected) 9.dp else 7.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ),
                )
            }
        }

        Button(
            enabled = enabled && !pagerState.isScrollInProgress,
            onClick = {
                if (isLastPage) {
                    onCheckDevice()
                } else {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.ScreenHorizontal)
                .padding(bottom = Spacing.XXL),
        ) {
            Text(
                text = if (isLastPage) {
                    stringResource(R.string.onboarding_check_device)
                } else {
                    stringResource(R.string.action_continue)
                },
            )
        }
    }
}

@Composable
private fun ResultsPage(
    snapshot: DeviceAiSnapshot,
    onFinish: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.onboarding_results_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(horizontal = Spacing.ScreenHorizontal)
                .padding(top = Spacing.XXL, bottom = Spacing.M)
                .semantics { heading() },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            AiTask.entries.forEach { task ->
                CapabilityRow(capability = snapshot[task])
            }
        }

        Button(
            onClick = onFinish,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.ScreenHorizontal)
                .padding(vertical = Spacing.XXL),
        ) {
            Text(stringResource(R.string.onboarding_get_started))
        }
    }
}

@Preview(name = "Onboarding - intro", showBackground = true)
@Composable
private fun OnboardingIntroPreview() {
    LocalAiTheme {
        OnboardingContent(
            stage = OnboardingStage.INTRO,
            snapshot = DeviceAiSnapshot(),
            onCheckDevice = {},
            onFinish = {},
        )
    }
}

@Preview(name = "Onboarding - checking", showBackground = true)
@Composable
private fun OnboardingCheckingPreview() {
    LocalAiTheme {
        OnboardingContent(
            stage = OnboardingStage.CHECKING,
            snapshot = DeviceAiSnapshot(),
            onCheckDevice = {},
            onFinish = {},
        )
    }
}
