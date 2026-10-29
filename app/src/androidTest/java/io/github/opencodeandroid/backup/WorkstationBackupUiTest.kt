package io.github.opencodeandroid.backup

import io.github.opencodeandroid.ui.nativeLabel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.OpenCodeAppTheme
import io.github.opencodeandroid.runtime.RuntimeState
import io.github.opencodeandroid.desktop.DesktopState
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class WorkstationBackupUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun unsavedEditorAndShortOrMismatchedPasswordsCannotBeginABackup() {
        assumeFalse(RuntimeState.current.value.busy || DesktopState.current.value.busy)
        val original = WorkstationBackupState.current.value
        var dirty by mutableStateOf(true)
        try {
            compose.runOnUiThread { compose.activity.setContent { OpenCodeAppTheme { Column(Modifier.verticalScroll(rememberScrollState())) { WorkstationBackupControls(dirty, false) {} } } } }
            compose.onNodeWithTag("workstation-export").assertIsNotEnabled()
            compose.runOnIdle { dirty = false }
            compose.onNodeWithTag("workstation-export").assertIsEnabled().performClick()
            compose.onNodeWithTag("workstation-password").performTextInput("short")
            compose.onNodeWithText(nativeLabel("开始加密")).assertIsNotEnabled()
            compose.onNodeWithTag("workstation-password").performTextClearance(); compose.onNodeWithTag("workstation-password").performTextInput("test-only-passphrase")
            compose.onNodeWithTag("workstation-password-repeat").performTextInput("different-passphrase")
            compose.onNodeWithText(nativeLabel("开始加密")).assertIsNotEnabled()
            compose.onNodeWithTag("workstation-password-repeat").performTextClearance(); compose.onNodeWithTag("workstation-password-repeat").performTextInput("test-only-passphrase")
            compose.onNodeWithText(nativeLabel("开始加密")).assertIsEnabled()
            compose.onNodeWithText(nativeLabel("取消"), useUnmergedTree = true).performClick()
            compose.onNodeWithTag("workstation-password").assertDoesNotExist()
            assertFalse(WorkstationBackupState.current.value.busy); assertNull(WorkstationBackupState.current.value.review)
        } finally { WorkstationBackupState.change { original } }
    }
    @Test fun importedImagesRequireNativeConfirmationAndChromiumCompatibilityDefaultsToOff() {
        val original = WorkstationBackupState.current.value
        try {
            compose.runOnUiThread { WorkstationBackupState.change { it.copy(review = WorkstationReview(UUID.randomUUID().toString(), 1_700_000_000_000, 42, 1048576, "2.1.4-dev.test", true), busy = false) } }
            compose.runOnUiThread { compose.activity.setContent { OpenCodeAppTheme { WorkstationBackupDialogs(false) } } }
            compose.onNodeWithTag("workstation-confirm-restore").assertIsEnabled()
            compose.onNode(isToggleable()).assertIsOff()
            compose.onNodeWithText(nativeLabel("同时启用 Chromium 兼容模式（关闭 Linux 进程沙箱）")).assertExists()
            assertFalse(WorkstationBackupState.current.value.busy)
            compose.runOnUiThread { WorkstationBackupState.change { it.copy(review = null, restartRequired = true, message = "test-only completed restore") } }
            compose.onNodeWithTag("workstation-reopen").assertIsDisplayed()
            // Never click confirm or start a real backup against the user's normal namespace.
        } finally { compose.runOnUiThread { WorkstationBackupState.change { original } } }
    }
}
