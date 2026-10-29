package io.github.opencodeandroid.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.runtime.LocalOpenCode
import io.github.opencodeandroid.runtime.RuntimeState
import org.hamcrest.Matchers.allOf
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class FullscreenWorkspaceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private var intentsActive = false
    @Before fun resetFixtureState() {
        ui.runOnIdle { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        ui.runOnIdle { RuntimeState.paired(null) }
    }
    @After fun cleanup() {
        if (intentsActive) Intents.release()
        ui.runOnIdle { RuntimeState.paired(null) }
    }

    @Test fun floatingNavigationFullscreenRotationAndExitConfirmation() {
        ui.runOnIdle { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        ui.onNodeWithText("OpenCode Android Full").assertDoesNotExist()
        ui.runOnIdle { assertFalse(WindowInsetsCompat.toWindowInsetsCompat(ui.activity.window.decorView.rootWindowInsets).isVisible(WindowInsetsCompat.Type.systemBars())) }
        ui.onNodeWithTag("workspace-orb").performClick()
        ui.onNodeWithTag("workspace-menu").assertIsDisplayed()
        capture("portrait-menu.png")
        ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithTag("workspace-menu").assertDoesNotExist()
        val start = ui.onNodeWithTag("workspace-orb").fetchSemanticsNode().boundsInRoot.center
        ui.onRoot().performTouchInput { swipe(start, Offset(80f, start.y - 100f), 500) }
        ui.waitForIdle()
        assertTrue(ui.onNodeWithTag("workspace-orb").fetchSemanticsNode().boundsInRoot.left < 200f)
        ui.onNodeWithTag("workspace-orb").performClick()
        ui.onNodeWithTag("workspace-destination-1").performClick()
        ui.runOnIdle { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        ui.onNodeWithTag("workspace-orb").performClick()
        ui.onNodeWithTag("workspace-destination-1").assertIsSelected()
        capture("landscape-menu.png")
        val bounds = ui.onNodeWithTag("workspace-orb").fetchSemanticsNode().boundsInRoot
        val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= root.right && bounds.bottom <= root.bottom)
        ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithText(nativeLabel("退出应用？")).assertIsDisplayed()
        ui.onNodeWithText(nativeLabel("取消")).performClick()
        ui.onNodeWithText(nativeLabel("退出应用？")).assertDoesNotExist()
        assertFalse(ui.activity.isFinishing)
    }

    @Test fun backNavigatesWebHistoryBeforeShowingExitDialog() {
        UiFixtureServer().use {
            openFixture()
            ui.runOnIdle { findWebView(ui.activity.window.decorView)!!.loadUrl(LocalOpenCode.ORIGIN + "/second") }
            waitForTitle("SECOND")
            ui.runOnIdle { findWebView(ui.activity.window.decorView)!!.evaluateJavascript("document.body.insertAdjacentHTML('beforeend','<div role=dialog>Modal</div>')", null) }
            waitForModal(true)
            ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
            waitForModal(false)
            waitForTitle("SECOND")
            ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
            waitForTitle("FIRST")
            ui.onNodeWithText(nativeLabel("退出应用？")).assertDoesNotExist()
            ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
            ui.waitUntil(5000) { ui.onAllNodesWithText(nativeLabel("退出应用？")).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(nativeLabel("退出应用？")).assertIsDisplayed()
            ui.onNodeWithText(nativeLabel("取消")).performClick()
        }
    }

    @Test fun imagePickerUploadsActualContentUriBytes() {
        UiFixtureServer().use { server ->
            preparePicker(false)
            openFixture()
            chooseAttachment()
            awaitUpload(server)
            val files = JSONArray(server.upload.get())
            assertEquals(1, files.length())
            assertEquals("pixel.png", files.getJSONObject(0).getString("name"))
            assertEquals("image/png", files.getJSONObject(0).getString("type"))
            assertTrue(files.getJSONObject(0).getString("data").endsWith(UploadFixtureProvider.PIXEL))
            intended(allOf(hasAction(Intent.ACTION_OPEN_DOCUMENT), hasType("image/*"), hasExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)))
        }
    }

    @Test fun multipleFilePickerUploadsBothFiles() {
        UiFixtureServer("*/*", true).use { server ->
            preparePicker(true)
            openFixture()
            chooseAttachment()
            intended(allOf(hasAction(Intent.ACTION_OPEN_DOCUMENT), hasExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)))
            awaitUpload(server)
            val files = JSONArray(server.upload.get())
            assertEquals(2, files.length())
            assertTrue(files.toString(), (0 until files.length()).none { files.getJSONObject(it).has("error") })
            assertEquals(setOf("pixel.png", "note.txt"), (0 until files.length()).map { files.getJSONObject(it).getString("name") }.toSet())
            assertTrue(files.toString().contains(android.util.Base64.encodeToString("UPLOAD_DOCUMENT_OK".toByteArray(), android.util.Base64.NO_WRAP)))
            intended(allOf(hasAction(Intent.ACTION_OPEN_DOCUMENT), hasExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)))
        }
    }

    @Test fun cancellingPickerAllowsAnotherSelection() {
        UiFixtureServer().use { server ->
            Intents.init(); intentsActive = true
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            openFixture()
            chooseAttachment()
            assertNull(server.upload.get())
            Intents.release(); intentsActive = false
            preparePicker(false)
            chooseAttachment()
            ui.waitUntil(10000) { server.upload.get() != null }
        }
    }

    private fun preparePicker(multiple: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.contentResolver.call(Uri.parse("content://io.github.opencodeandroid.test.control"), "grantFixture", context.packageName, null)
        val image = Uri.parse("content://io.github.opencodeandroid.test.upload/pixel.png")
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, context.checkUriPermission(image, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
        val result = Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
            if (multiple) clipData = ClipData.newUri(context.contentResolver, "attachments", image).apply { addItem(ClipData.Item(Uri.parse("content://io.github.opencodeandroid.test.upload/note.txt"))) }
            else data = image
        }
        Intents.init(); intentsActive = true
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, result))
    }

    private fun openFixture() {
        ui.runOnIdle { RuntimeState.paired(LocalOpenCode.ORIGIN) }
        ui.onNodeWithTag("workspace-orb").performClick()
        ui.onNodeWithTag("workspace-destination-0").performClick()
        ui.waitForIdle()
        ui.runOnIdle {
            val browser = requireNotNull(findWebView(ui.activity.window.decorView))
            browser.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            browser.loadUrl(LocalOpenCode.ORIGIN + "/fixture-${java.util.UUID.randomUUID()}")
        }
        waitForTitle("FIRST")
        val complete = java.util.concurrent.atomic.AtomicBoolean(false)
        ui.waitUntil(10000) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                findWebView(ui.activity.window.decorView)?.evaluateJavascript("document.readyState === 'complete' && !!document.getElementById('attachment')") { complete.set(it == "true") }
            }
            complete.get()
        }
        val visual = java.util.concurrent.CountDownLatch(1)
        ui.runOnIdle {
            findWebView(ui.activity.window.decorView)!!.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { visual.countDown() }
            })
        }
        assertTrue("Fixture did not produce a visual frame", visual.await(5, java.util.concurrent.TimeUnit.SECONDS))
        ui.runOnIdle { findWebView(ui.activity.window.decorView)!!.clearHistory() }
    }

    private fun chooseAttachment() {
        onView(isAssignableFrom(WebView::class.java)).perform(click())
        ui.waitUntil(5000) { Intents.getIntents().any { it.action == Intent.ACTION_OPEN_DOCUMENT } }
        intended(hasAction(Intent.ACTION_OPEN_DOCUMENT))
    }

    private fun waitForModal(expected: Boolean) {
        val visible = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        ui.waitUntil(5000) {
            ui.runOnIdle { findWebView(ui.activity.window.decorView)!!.evaluateJavascript("!!document.querySelector('[role=dialog]')") { visible.set(it == "true") } }
            visible.get() == expected
        }
    }

    private fun awaitUpload(server: UiFixtureServer) {
        try { ui.waitUntil(10000) { server.upload.get() != null } }
        catch (failure: ComposeTimeoutException) {
            val state = java.util.concurrent.atomic.AtomicReference<String>()
            ui.runOnIdle { findWebView(ui.activity.window.decorView)!!.evaluateJavascript("JSON.stringify({files:Array.from(document.getElementById('attachment').files).map(f=>({name:f.name,size:f.size})),error:window.uploadError})") { state.set(it) } }
            ui.waitUntil(2000) { state.get() != null }
            throw AssertionError(state.get(), failure)
        }
    }

    private fun waitForTitle(expected: String) {
        try { ui.waitUntil(10000) {
            var title: String? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync { title = findWebView(ui.activity.window.decorView)?.title }
            title == expected
        } } catch (failure: ComposeTimeoutException) {
            var details = ""
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val view = findWebView(ui.activity.window.decorView)
                details = "view=${view != null}, url=${view?.url}, title=${view?.title}, pairing=${RuntimeState.current.value.pairingUrl != null}, activityLoader=${ui.activity.javaClass.classLoader === RuntimeState::class.java.classLoader}, files=${ui.activity.filesDir}, installed=${java.io.File(ui.activity.filesDir, "rootfs/.installed-pack").isFile}"
            }
            throw AssertionError("Expected title $expected; $details", failure)
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun capture(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ui-checks").apply { mkdirs() }
        ui.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}
