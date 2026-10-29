package io.github.opencodeandroid.ui

import io.github.opencodeandroid.runtime.WorkspaceContext
import io.github.opencodeandroid.startupSessionReplaces
import org.junit.Assert.*
import org.junit.Test

class WorkspaceBrowserRestoreTest {
    @Test fun aLegacyRootSessionInAnOldActivityBundleCannotOverrideTheConfirmedProject() {
        assertTrue(startupSessionReplaces(WorkspaceContext.sessionUrl("session_legacy_root"), WorkspaceContext.sessionUrl("session_project")))
        assertTrue(startupSessionReplaces("http://127.0.0.1:49374/", WorkspaceContext.sessionUrl("session_project")))
    }
    @Test fun theSameSavedSessionKeepsItsPageAndAnUntrustedStartupUrlIsIgnored() {
        val session=WorkspaceContext.sessionUrl("session_project")
        assertFalse(startupSessionReplaces(session, session))
        assertFalse(startupSessionReplaces(session, "https://fixture.invalid/session/remote"))
        assertFalse(startupSessionReplaces(session, "http://127.0.0.1:49374/auth/connect/not-a-session"))
        assertFalse(startupSessionReplaces(session, null))
    }
}
