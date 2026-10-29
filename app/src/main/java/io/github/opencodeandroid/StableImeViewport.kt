package io.github.opencodeandroid

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Resize Chromium to the final keyboard viewport once instead of on every IME
 * animation frame. Android keeps animating the keyboard; page state is retained.
 * The target also settles correctly when an animation is interrupted or reversed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Modifier.stableImeViewport() = windowInsetsPadding(WindowInsets.imeAnimationTarget)
