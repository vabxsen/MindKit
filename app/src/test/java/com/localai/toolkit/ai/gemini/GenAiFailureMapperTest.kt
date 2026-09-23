package com.localai.toolkit.ai.gemini

import com.google.common.truth.Truth.assertThat
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiFailure
import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CancellationException
import org.junit.Test

/**
 * The boundary that decides what a user reads when on-device AI fails.
 *
 * Every mapping here is asserted because the alternative - a wrong or generic message -
 * is the difference between "your device needs a system update" and "something went
 * wrong", and because these codes were read off the shipped ML Kit artifact rather than
 * guessed.
 */
class GenAiFailureMapperTest {

    @Test
    fun `unsupported codes map to Unsupported`() {
        listOf(
            GenAiException.ErrorCode.NOT_AVAILABLE,
            GenAiException.ErrorCode.NOT_SUPPORTED,
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
        ).forEach { code ->
            assertThat(exception(code).toAiFailure()).isInstanceOf(AiFailure.Unsupported::class.java)
        }
    }

    @Test
    fun `busy maps to Busy`() {
        val failure = exception(GenAiException.ErrorCode.BUSY).toAiFailure()

        assertThat(failure).isInstanceOf(AiFailure.Busy::class.java)
    }

    @Test
    fun `battery quota maps to QuotaExceeded rather than a generic error`() {
        val failure =
            exception(GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED).toAiFailure()

        assertThat(failure).isInstanceOf(AiFailure.QuotaExceeded::class.java)
    }

    @Test
    fun `background restriction maps to BackgroundBlocked`() {
        val failure = exception(GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED).toAiFailure()

        assertThat(failure).isInstanceOf(AiFailure.BackgroundBlocked::class.java)
    }

    @Test
    fun `input size errors distinguish too large from too small`() {
        val tooLarge = exception(GenAiException.ErrorCode.REQUEST_TOO_LARGE).toAiFailure()
        val tooSmall = exception(GenAiException.ErrorCode.REQUEST_TOO_SMALL).toAiFailure()

        assertThat((tooLarge as AiFailure.InvalidInput).tooLarge).isTrue()
        assertThat((tooSmall as AiFailure.InvalidInput).tooLarge).isFalse()
    }

    @Test
    fun `storage and system update codes map to their own failures`() {
        assertThat(exception(GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE).toAiFailure())
            .isInstanceOf(AiFailure.NotEnoughStorage::class.java)
        assertThat(exception(GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE).toAiFailure())
            .isInstanceOf(AiFailure.NeedsSystemUpdate::class.java)
    }

    @Test
    fun `invalid image maps to InvalidImage`() {
        assertThat(exception(GenAiException.ErrorCode.INVALID_INPUT_IMAGE).toAiFailure())
            .isInstanceOf(AiFailure.InvalidImage::class.java)
    }

    @Test
    fun `an unrecognised code degrades to Unknown instead of being guessed`() {
        // A future ML Kit release could add codes this build has never seen.
        assertThat(exception(errorCode = 99_999).toAiFailure())
            .isInstanceOf(AiFailure.Unknown::class.java)
    }

    @Test
    fun `a wrapped GenAiException is found through the cause chain`() {
        // ML Kit resolves its futures on background executors, so the real cause is
        // usually wrapped in an ExecutionException.
        val wrapped = ExecutionException(exception(GenAiException.ErrorCode.BUSY))

        assertThat(wrapped.toAiFailure()).isInstanceOf(AiFailure.Busy::class.java)
    }

    @Test
    fun `a non-GenAi throwable maps to Unknown`() {
        assertThat(IOException("disk").toAiFailure()).isInstanceOf(AiFailure.Unknown::class.java)
    }

    @Test
    fun `cancellation is reported as Cancelled rather than an error`() {
        assertThat(CancellationException("stopped").toAiFailure())
            .isInstanceOf(AiFailure.Cancelled::class.java)
    }

    @Test
    fun `technical detail carries the code but never a stack trace`() {
        val failure = exception(GenAiException.ErrorCode.BUSY, "engine busy").toAiFailure()

        val detail = failure.technicalDetail.orEmpty()
        assertThat(detail).contains("code=${GenAiException.ErrorCode.BUSY}")
        assertThat(detail).contains("engine busy")
        assertThat(detail).doesNotContain("\tat ")
    }

    @Test
    fun `feature status ints map onto capability statuses`() {
        assertThat(FeatureStatus.AVAILABLE.toCapabilityStatus())
            .isEqualTo(AiCapabilityStatus.AVAILABLE)
        assertThat(FeatureStatus.DOWNLOADABLE.toCapabilityStatus())
            .isEqualTo(AiCapabilityStatus.DOWNLOADABLE)
        assertThat(FeatureStatus.DOWNLOADING.toCapabilityStatus())
            .isEqualTo(AiCapabilityStatus.DOWNLOADING)
        assertThat(FeatureStatus.UNAVAILABLE.toCapabilityStatus())
            .isEqualTo(AiCapabilityStatus.UNSUPPORTED)
    }

    @Test
    fun `an unknown feature status is not reported as unsupported`() {
        // Claiming "your device cannot do this" on an unrecognised value would be a
        // false statement about the hardware.
        assertThat((-7).toCapabilityStatus()).isEqualTo(AiCapabilityStatus.UNKNOWN)
    }

    private fun exception(errorCode: Int, message: String = "test"): GenAiException =
        GenAiException(message, null, errorCode)
}
