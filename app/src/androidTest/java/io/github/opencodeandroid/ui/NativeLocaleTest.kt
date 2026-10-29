package io.github.opencodeandroid.ui

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.FloatingWorkspaceNavigation
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.OpenCodeAppTheme
import io.github.opencodeandroid.R
import io.github.opencodeandroid.i18n.NativeStrings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class NativeLocaleTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun systemLocaleChangesUpdateVisibleNavigationAndRtlWithoutChangingUserText() {
        var tag by mutableStateOf("en")
        var direction = LayoutDirection.Ltr
        ui.runOnUiThread { ui.activity.setContent {
            val configuration = remember(tag) { Configuration(ui.activity.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) } }
            val context = remember(tag) { ui.activity.createConfigurationContext(configuration) }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                LocalLayoutDirection provides if (configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                val currentDirection = LocalLayoutDirection.current
                SideEffect { direction = currentDirection }
                OpenCodeAppTheme { FloatingWorkspaceNavigation(0,false,true,{},onSelect={}) }
            }
        } }
        ui.onNodeWithText("Files").assertIsDisplayed()
        ui.runOnIdle { tag="ja" }; ui.onNodeWithText("ファイル").assertIsDisplayed()
        ui.runOnIdle { tag="ar" }; ui.onNodeWithText("الملفات").assertIsDisplayed()
        ui.runOnIdle { assertEquals(LayoutDirection.Rtl,direction) }
        val context=ui.activity.createConfigurationContext(Configuration(ui.activity.resources.configuration).apply { setLocale(Locale.JAPANESE) })
        val strings=NativeStrings(context.resources)
        val path="/workspace/保存/ملف % 例.txt"
        assertTrue(strings.text(R.string.native_0e822affd5da,path).endsWith(path))
    }
    @Test fun unsupportedLocaleFallsBackToEnglishAndOrderedSystemLocalesAreRespected() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        fun strings(tags:String)=NativeStrings(context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tags)) }).resources)
        assertEquals("Terminal",strings("zz").message("终端"))
        assertEquals("ターミナル",strings("zz,ja").message("终端"))
        assertEquals("终端",strings("zh-Hans").message("终端"))
    }
}
