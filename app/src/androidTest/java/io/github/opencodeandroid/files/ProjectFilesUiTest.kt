package io.github.opencodeandroid.files

import io.github.opencodeandroid.ui.nativeLabel

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.runtime.RuntimePaths
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import io.github.opencodeandroid.runtime.WorkspaceContext
import java.io.File
import java.nio.file.Files
import java.util.UUID

class ProjectFilesUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private var originalDirectory: String? = null
    @Before fun phoneWindow() {
        ui.runOnIdle { originalDirectory = WorkspaceContext.directory.value; WorkspaceContext.select("/workspace") }
        ui.runOnIdle { ui.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
    }
    @After fun restoreDirectory() { originalDirectory?.let { directory -> ui.runOnIdle { WorkspaceContext.select(directory) } } }
    @OptIn(ExperimentalTestApi::class)
    @Test fun rightClickAndLongPressOpenFileActionsWithoutOpeningEditor() {
        val paths = RuntimePaths(InstrumentationRegistry.getInstrumentation().targetContext).apply { prepare() }
        val name = ".ui-mouse-${UUID.randomUUID()}.txt"
        val fixture = File(paths.workspace, name).apply { writeText("context menu fixture") }
        try {
            ui.onNodeWithTag("workspace-orb").performClick(); ui.onNodeWithTag("workspace-destination-3").performClick()
            ui.waitUntil(5000) { ui.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(name).performMouseInput { click(button = MouseButton.Secondary) }
            ui.onNodeWithText(nativeLabel("重命名"), useUnmergedTree = true).assertIsDisplayed()
            ui.onNodeWithTag("files-editor").assertDoesNotExist()
            ui.onNodeWithText(nativeLabel("取消")).performClick()
            ui.onNodeWithText(name).performTouchInput { longClick() }
            ui.onNodeWithText(nativeLabel("重命名"), useUnmergedTree = true).assertIsDisplayed()
            ui.onNodeWithTag("files-editor").assertDoesNotExist()
            ui.onNodeWithText(nativeLabel("取消")).performClick()
            assertEquals("context menu fixture", fixture.readText())
        } finally { check(fixture.canonicalFile.parentFile == paths.workspace.canonicalFile); fixture.delete() }
    }
    @Test fun copyMoveAndShareFromFileMenu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paths = RuntimePaths(context).apply { prepare() }
        val name = ".ui-transfer-${UUID.randomUUID()}"
        val fixture = File(paths.workspace, name).apply { mkdir() }
        File(fixture, "from").mkdir(); File(fixture, "to").mkdir()
        File(fixture, "from/code.txt").writeText("UI TRANSFER")
        androidx.test.espresso.intent.Intents.init()
        androidx.test.espresso.intent.Intents.intending(androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(android.content.Intent.ACTION_CHOOSER)).respondWith(android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null))
        try {
            ui.onNodeWithTag("workspace-orb").performClick(); ui.onNodeWithTag("workspace-destination-3").performClick()
            ui.waitUntil(5000) { ui.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(name).performClick(); ui.onNodeWithText("from").performClick()
            ui.onNodeWithTag("file-actions-code.txt").performClick(); ui.onNodeWithText(nativeLabel("复制"), useUnmergedTree = true).performClick()
            ui.onNodeWithText(nativeLabel("返回")).performClick(); ui.onNodeWithText("to").performClick(); ui.onNodeWithText(nativeLabel("粘贴")).performClick()
            ui.waitUntil(5000) { File(fixture, "to/code.txt").isFile }
            ui.onNodeWithTag("file-actions-code.txt").performClick(); ui.onNodeWithText(nativeLabel("移动到…"), useUnmergedTree = true).performClick()
            ui.onNodeWithText(nativeLabel("返回")).performClick(); ui.onNodeWithText("from").performClick(); ui.onNodeWithText(nativeLabel("粘贴")).performClick()
            ui.waitUntil(5000) { File(fixture, "from/code (1).txt").isFile }
            assertFalse(File(fixture, "to/code.txt").exists())
            ui.onNodeWithTag("file-actions-code (1).txt").performClick(); ui.onNodeWithText(nativeLabel("分享文件"), useUnmergedTree = true).performClick()
            ui.waitUntil(5000) { androidx.test.espresso.intent.Intents.getIntents().any { it.action == android.content.Intent.ACTION_CHOOSER } }
            assertEquals("UI TRANSFER", File(fixture, "from/code.txt").readText())
        } finally {
            androidx.test.espresso.intent.Intents.release()
            check(fixture.canonicalFile.parentFile == paths.workspace.canonicalFile)
            Files.walk(fixture.toPath()).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun createEditRotateAndBackProtectsUnsavedText() {
        val paths = RuntimePaths(InstrumentationRegistry.getInstrumentation().targetContext)
        val name = ".ui-files-${UUID.randomUUID()}"
        val fixture = File(paths.workspace, name)
        try {
            ui.onNodeWithTag("workspace-orb").performClick()
            ui.onNodeWithTag("workspace-destination-3").performClick()
            ui.onNodeWithText(nativeLabel("新建文件夹")).performClick()
            ui.onNodeWithTag("files-name").performTextInput(name)
            ui.onNodeWithText(nativeLabel("确定")).performClick()
            ui.waitUntil(5000) { fixture.isDirectory }
            ui.onNodeWithText(name).performClick()
            ui.onNodeWithText(nativeLabel("新建文件")).performClick()
            ui.onNodeWithTag("files-name").performTextInput("hello.txt")
            ui.onNodeWithText(nativeLabel("确定")).performClick()
            val textFile = File(fixture, "hello.txt")
            ui.waitUntil(5000) { textFile.isFile }
            ui.onNodeWithText("hello.txt").performClick()
            ui.onNodeWithTag("files-editor").performTextInput("文件编辑已就绪\n")
            ui.onNodeWithTag("files-save").performClick()
            ui.waitUntil(5000) { textFile.readText() == "文件编辑已就绪\n" }
            ui.onNodeWithTag("files-editor").performTextInput("unsaved")
            ui.runOnIdle { ui.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            ui.onNodeWithTag("files-editor").assertTextContains("unsaved", substring = true)
            ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
            ui.onNodeWithText(nativeLabel("放弃未保存的更改？")).assertIsDisplayed()
            ui.onNodeWithText(nativeLabel("继续编辑")).performClick()
            ui.onNodeWithTag("files-save").performClick()
            ui.waitUntil(5000) { textFile.readText().contains("unsaved") }
            ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
            ui.onNodeWithText("hello.txt").assertIsDisplayed()
            ui.onNodeWithText(nativeLabel("退出应用？")).assertDoesNotExist()
        } finally {
            check(fixture.canonicalFile.parentFile == paths.workspace.canonicalFile)
            if (fixture.exists()) Files.walk(fixture.toPath()).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
