package io.github.opencodeandroid.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.FloatingWorkspaceNavigation
import io.github.opencodeandroid.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OrbTransparencyTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun transparentOrbLeavesItsSquareCornersUnpainted() {
        val preferences = ui.activity.getSharedPreferences("workspace-navigation", Context.MODE_PRIVATE)
        val old = preferences.all["orb-opacity"] as? Float
        try {
            var expanded by mutableStateOf(false)
            ui.runOnUiThread { ui.activity.setContent { MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                    FloatingWorkspaceNavigation(0, false, expanded, { expanded = it }, onSelect = {})
                }
            } } }
            for (opacity in listOf(.2f, .4f, .9f, 1f)) {
                ui.onNodeWithTag("workspace-orb").performClick()
                ui.onNodeWithTag("orb-opacity").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(opacity) }
                ui.onNodeWithTag("workspace-orb").performClick(); ui.waitForIdle()
                val rect = ui.onNodeWithTag("workspace-orb").fetchSemanticsNode().boundsInWindow
                val done = CountDownLatch(1); val status = AtomicInteger(-1)
                lateinit var bitmap: Bitmap
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val decor = ui.activity.window.decorView
                    bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                    android.view.PixelCopy.request(ui.activity.window, bitmap, { status.set(it); done.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
                }
                assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(android.view.PixelCopy.SUCCESS, status.get())
                val inset = (rect.width * .04f).toInt().coerceAtLeast(2)
                for (x in listOf(rect.left.toInt() + inset, rect.right.toInt() - inset)) for (y in listOf(rect.top.toInt() + inset, rect.bottom.toInt() - inset)) {
                    val color = bitmap.getPixel(x, y)
                    assertTrue("Orb corner retained an alpha/shadow rectangle at $opacity", android.graphics.Color.red(color) >= 253 && android.graphics.Color.green(color) <= 2 && android.graphics.Color.blue(color) >= 253)
                }
                bitmap.recycle()
            }
        } finally { preferences.edit().apply { if (old == null) remove("orb-opacity") else putFloat("orb-opacity", old) }.commit() }
    }
}
