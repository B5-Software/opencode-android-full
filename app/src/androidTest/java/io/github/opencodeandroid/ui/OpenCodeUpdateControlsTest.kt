package io.github.opencodeandroid.ui

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.runtime.*
import io.github.opencodeandroid.runtime.update.OpenCodeUpdateState
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class OpenCodeUpdateControlsTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun nativePickerSignedReviewStopGuardAndCancelNeverActivateCode() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("includeHostSignedUpdate") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paths = RuntimePaths(context)
        val selection = File(paths.config, "opencode-active.json")
        val before = selection.takeIf { it.exists() }?.readText()
        val snapshot = RuntimeState.current.value
        val bundle = File(context.getExternalFilesDir(null), "updates/native-signed.ocaupdate"); check(bundle.isFile)
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
            ui.onNodeWithTag("workspace-orb").performClick(); ui.onNodeWithTag("workspace-destination-4").performClick()
            ui.onNodeWithText(nativeLabel("选择更新包")).performScrollTo().performClick()
            intended(hasAction(Intent.ACTION_OPEN_DOCUMENT))
            assertNull(OpenCodeUpdateState.current.value.candidate)
            // Exercise the same content-resolver importer with an app-owned fixture URI.
            ui.runOnIdle { OpenCodeUpdateState.import(context, Uri.fromFile(bundle)) }
            ui.waitUntil(60_000) { !OpenCodeUpdateState.current.value.busy }
            assertNotNull(OpenCodeUpdateState.current.value.candidate)
            ui.onNodeWithText(nativeLabel("安装此更新")).performScrollTo().assertIsEnabled()
            ui.runOnIdle { RuntimeState.update("fixture running", true, true) }
            ui.onNodeWithText(nativeLabel("安装此更新")).assertIsNotEnabled()
            ui.runOnIdle { RuntimeState.update("Stopped", false, true) }
            ui.onNodeWithText(nativeLabel("安装此更新")).performClick()
            ui.onNodeWithText(nativeLabel("安装 OpenCode 更新？")).assertIsDisplayed()
            ui.onNodeWithText(nativeLabel("确认安装")).assertIsDisplayed()
            ui.onNodeWithText(nativeLabel("取消")).performClick()
            assertEquals(before, selection.takeIf { it.exists() }?.readText())
            assertFalse(File(paths.config, "opencode-update-journal.json").exists())
        } finally {
            Intents.release(); OpenCodeUpdateState.forgetCandidate(context); OpenCodeUpdateState.finished("")
            ui.runOnIdle { RuntimeState.update(snapshot.phase, snapshot.busy, snapshot.ready) }
            bundle.delete()
        }
    }
}
