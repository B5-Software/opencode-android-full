package io.github.opencodeandroid.ui

import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.i18n.NativeStrings

/** Locate app-owned controls in the device's language; fixture/user data stays literal. */
internal fun nativeLabel(source: String) = NativeStrings(InstrumentationRegistry.getInstrumentation().targetContext.resources).message(source)
