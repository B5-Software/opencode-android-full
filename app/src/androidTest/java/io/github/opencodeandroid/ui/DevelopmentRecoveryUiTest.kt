package io.github.opencodeandroid.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.opencodeandroid.*
import io.github.opencodeandroid.runtime.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class DevelopmentRecoveryUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun recoveryRequiresUserActionAndBusyServicesDisableRestart() {
        val run = DevelopmentRun(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 123, "test.fixture",
            DevelopmentOperation.OPENCODE, 0, setOf(1, 2), exitReason = 2)
        var status by mutableStateOf(DevelopmentRecoverySnapshot(DevelopmentHistory(runtime = DevelopmentSlot(interrupted = run, acknowledged = false)), loaded = true))
        var runtime by mutableStateOf(RuntimeSnapshot(ready = true))
        var desktopBusy by mutableStateOf(false)
        var starts = 0
        ui.runOnUiThread { ui.activity.setContent { OpenCodeAppTheme { DevelopmentRecoveryPanel(status, runtime, desktopBusy, { starts++ }, {}) } } }
        ui.onNodeWithText(nativeLabel("运行中断与恢复")).assertIsDisplayed()
        ui.onNodeWithText(nativeLabel("2 个终端未正常关闭，执行中的命令已中断。")).assertExists()
        ui.runOnIdle { assertEquals(0, starts) }
        ui.onNodeWithText(nativeLabel("重新启动 OpenCode")).assertIsEnabled().performClick()
        ui.runOnIdle { assertEquals(1, starts); runtime = runtime.copy(busy = true) }
        ui.onNodeWithText(nativeLabel("重新启动 OpenCode")).assertIsNotEnabled()
        ui.runOnIdle { runtime = runtime.copy(busy = false); desktopBusy = true }
        ui.onNodeWithText(nativeLabel("重新启动 OpenCode")).assertIsNotEnabled()
        ui.runOnIdle { desktopBusy = false; status = status.copy(history = status.history.copy(runtime = status.history.runtime.copy(acknowledged = true))) }
        ui.onNodeWithText(nativeLabel("重新启动 OpenCode")).assertDoesNotExist()
        ui.onNodeWithText(nativeLabel("已确认重新启动；旧命令没有重放。")).assertExists()
    }
    @Test fun repairIsExplicitAndNeverRunsWhileAServiceIsBusy() {
        var runtime by mutableStateOf(RuntimeSnapshot(busy = true))
        var repairs = 0; var starts = 0
        ui.runOnUiThread { ui.activity.setContent { OpenCodeAppTheme {
            DevelopmentRecoveryPanel(DevelopmentRecoverySnapshot(error = true, loaded = true), runtime, false, { starts++ }, { repairs++ })
        } } }
        ui.onNodeWithText(nativeLabel("修复运行记录")).assertIsNotEnabled()
        ui.runOnIdle { assertEquals(0, repairs); assertEquals(0, starts); runtime = runtime.copy(busy = false) }
        ui.onNodeWithText(nativeLabel("修复运行记录")).assertIsEnabled().performClick()
        ui.runOnIdle { assertEquals(1, repairs); assertEquals(0, starts) }
    }
}
