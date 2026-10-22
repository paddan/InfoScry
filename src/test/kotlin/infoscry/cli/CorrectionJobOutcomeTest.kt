package infoscry.cli

import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CorrectionJobOutcomeTest {

    private fun job(state: JobState, code: String? = null) = Job(
        id = JobId.new(), type = JobType.CORRECTIONS, state = state, createdAt = "t", updatedAt = "t", errorCode = code,
    )

    @Test
    fun `a failed or cancelled correction job fails the command`() {
        assertFailsWith<CliFailure> { requireCorrectionsApplied(job(JobState.FAILED, "EMBEDDING_UNAVAILABLE")) }
        assertFailsWith<CliFailure> { requireCorrectionsApplied(job(JobState.CANCELLED)) }
        requireCorrectionsApplied(job(JobState.COMPLETE))
    }
}
