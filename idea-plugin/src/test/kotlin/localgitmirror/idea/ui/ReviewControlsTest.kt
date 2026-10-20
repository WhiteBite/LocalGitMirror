package localgitmirror.idea.ui

import localgitmirror.idea.deps.MachineRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewControlsTest {

    @Test
    fun `work role enables both work-only buttons`() {
        val p = reviewWorkButtonPolicy(MachineRole.WORK)
        assertTrue(p.enabled)
        assertNull(p.reasonKey)
    }

    @Test
    fun `home role keeps buttons disabled with the home reason`() {
        val p = reviewWorkButtonPolicy(MachineRole.HOME)
        assertFalse(p.enabled)
        assertEquals(REVIEW_ROLE_HOME_KEY, p.reasonKey)
    }

    @Test
    fun `undetermined role keeps buttons disabled with the undetermined reason`() {
        val p = reviewWorkButtonPolicy(MachineRole.UNDETERMINED)
        assertFalse(p.enabled)
        assertEquals(REVIEW_ROLE_UNDETERMINED_KEY, p.reasonKey)
    }

    @Test
    fun `send selected enabled only for work role with gitlab rows`() {
        val p = sendSelectedPolicy(MachineRole.WORK, hasGitlabSelection = true)
        assertTrue(p.enabled)
        assertNull(p.reasonKey)
    }

    @Test
    fun `send selected disabled for home and undetermined roles with the role reason`() {
        for (role in listOf(MachineRole.HOME, MachineRole.UNDETERMINED)) {
            val p = sendSelectedPolicy(role, hasGitlabSelection = true)
            assertFalse(p.enabled, "role $role must disable the popup item")
            assertEquals(REVIEW_SEND_ROLE_KEY, p.reasonKey, "role $role")
        }
    }

    @Test
    fun `send selected disabled without gitlab rows even on work`() {
        val p = sendSelectedPolicy(MachineRole.WORK, hasGitlabSelection = false)
        assertFalse(p.enabled)
        assertEquals(REVIEW_SEND_SELECTION_KEY, p.reasonKey)
    }

    @Test
    fun `pending state key on work follows the autoPushReplies setting`() {
        assertEquals(REVIEW_STATE_PENDING_WORK_KEY, reviewReplyStateKey("pending", MachineRole.WORK, autoPushReplies = false))
        assertEquals(REVIEW_STATE_PENDING_AUTO_KEY, reviewReplyStateKey("pending", MachineRole.WORK, autoPushReplies = true))
    }

    @Test
    fun `pending state key off work ignores the setting`() {
        assertEquals("review.state.pending", reviewReplyStateKey("pending", MachineRole.HOME, autoPushReplies = true))
        assertEquals("review.state.pending", reviewReplyStateKey("pending", MachineRole.UNDETERMINED, autoPushReplies = false))
    }

    @Test
    fun `non-pending reply states keep their plain key`() {
        assertEquals("review.state.posted", reviewReplyStateKey("posted", MachineRole.WORK, autoPushReplies = false))
        assertEquals("review.state.failed", reviewReplyStateKey("failed", MachineRole.HOME, autoPushReplies = true))
    }
}
