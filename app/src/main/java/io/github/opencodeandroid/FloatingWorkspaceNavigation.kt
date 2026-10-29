package io.github.opencodeandroid

import io.github.opencodeandroid.i18n.rememberNativeStrings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal data class WorkspaceDestination(val label: String, val detail: String)
internal val WorkspaceDestinations = listOf(
    WorkspaceDestination("OpenCode", "代码与会话"), WorkspaceDestination("终端", "Debian Bash"),
    WorkspaceDestination("桌面", "图形工作区"), WorkspaceDestination("文件", "项目文件"), WorkspaceDestination("运行环境", "服务与开发工具"))

@Composable
internal fun FloatingWorkspaceNavigation(selected: Int, running: Boolean, expanded: Boolean, onExpanded: (Boolean) -> Unit,
    onWorkspaceOptions: (() -> Unit)? = null, workspaceOptionsLabel: String = "工作区布局", onSelect: (Int) -> Unit) {
    val strings = rememberNativeStrings()
    val context = LocalContext.current
    val placement = remember { context.getSharedPreferences("workspace-navigation", android.content.Context.MODE_PRIVATE) }
    val appearance = remember { NavigationAppearanceStore(placement) }
    var opacity by remember { mutableFloatStateOf(appearance.opacity()) }
    val colors = MaterialTheme.colorScheme
    val orbInk = colors.onPrimaryContainer
    var right by remember { mutableStateOf(placement.getBoolean("orb-right", true)) }
    var position by remember { mutableFloatStateOf(placement.getFloat("orb-position", 0.58f).coerceIn(0f, 1f)) }
    var dragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val ball = with(density) { 52.dp.toPx() }
        val gap = with(density) { 10.dp.toPx() }
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val maxX = (width - ball - gap).coerceAtLeast(gap)
        val maxY = (height - ball - gap).coerceAtLeast(gap)
        val x = if (dragging) dragX.coerceIn(gap, maxX) else if (right) maxX else gap
        val y = if (dragging) dragY.coerceIn(gap, maxY) else gap + (maxY - gap) * position
        val target = IntOffset(x.roundToInt(), y.roundToInt())
        val snapped by animateIntOffsetAsState(target, if (dragging) snap() else spring(dampingRatio = 0.82f, stiffness = 420f), label = "orb position")
        val offset = if (dragging) target else snapped
        if (expanded) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.24f)).clickable(onClickLabel = strings.text(R.string.native_70850bdeeabd)) { onExpanded(false) })
        val panelWidth = with(density) { 218.dp.toPx() }.coerceAtMost((width - ball - 3 * gap).coerceAtLeast(120f))
        val panelHeight = with(density) { (if (onWorkspaceOptions != null) 524.dp else 466.dp).toPx() }.coerceAtMost((height - 2 * gap).coerceAtLeast(0f))
        val panelX = (if (right) x - panelWidth - gap else x + ball + gap).coerceIn(gap, (width - panelWidth - gap).coerceAtLeast(gap))
        val panelY = (y + ball / 2 - panelHeight / 2).coerceIn(gap, (height - panelHeight - gap).coerceAtLeast(gap))
        AnimatedVisibility(expanded, Modifier.offset { IntOffset(panelX.roundToInt(), panelY.roundToInt()) }, enter = fadeIn() + scaleIn(initialScale = 0.94f), exit = fadeOut() + scaleOut(targetScale = 0.94f)) {
            Surface(Modifier.width(with(density) { panelWidth.toDp() }).heightIn(max = with(density) { panelHeight.toDp() }).testTag("workspace-menu"),
                shape = RoundedCornerShape(26.dp), color = colors.surfaceContainerHigh, shadowElevation = 20.dp, border = BorderStroke(1.dp, colors.outlineVariant)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(strings.text(R.string.native_b4549acb48e8), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Box(Modifier.size(6.dp).background(if (running) colors.primary else colors.outline, CircleShape))
                    }
                    WorkspaceDestinations.forEachIndexed { index, destination ->
                        val active = index == selected
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(if (active) colors.secondaryContainer else Color.Transparent)
                            .selectable(active, role = Role.Tab, onClick = { onSelect(index); onExpanded(false) }).testTag("workspace-destination-$index").padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            WorkspaceGlyph(index, if (active) colors.onSecondaryContainer else colors.onSurfaceVariant, Modifier.size(22.dp))
                            Column(Modifier.weight(1f)) {
                                Text(strings.message(destination.label), style = MaterialTheme.typography.labelLarge, color = if (active) colors.onSecondaryContainer else colors.onSurface)
                                Text(strings.message(destination.detail), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                    if (onWorkspaceOptions != null) {
                        HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = colors.outlineVariant)
                        TextButton(onWorkspaceOptions, Modifier.fillMaxWidth().testTag("workspace-layout-options")) { Text(strings.message(workspaceOptionsLabel)) }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = colors.outlineVariant)
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(strings.text(R.string.native_3d3551f4660f, (opacity * 100).roundToInt()), style = MaterialTheme.typography.labelMedium)
                        Slider(opacity, { opacity = NavigationAppearanceStore.normalize(it); appearance.opacity(opacity) }, valueRange = .2f..1f, steps = 15, modifier = Modifier.testTag("orb-opacity"))
                        Text(strings.text(R.string.native_26b155693efb), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        Box(Modifier.offset { offset }.size(52.dp).graphicsLayer {
            alpha = opacity
            shape = CircleShape
            clip = true
            shadowElevation = 0f
        }
            .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.secondaryContainer)), CircleShape)
            .clip(CircleShape).clickable(role = Role.Button, onClick = { onExpanded(!expanded) })
            .semantics { contentDescription = if (expanded) strings.text(R.string.native_70850bdeeabd) else strings.text(R.string.native_e031beabfc9a); stateDescription = strings.message(WorkspaceDestinations[selected].label) }
            .testTag("workspace-orb")
            .pointerInput(width, height, right, position) {
                detectDragGestures(onDragStart = { dragging = true; dragX = x; dragY = y; onExpanded(false) },
                    onDragEnd = { right = dragX + ball / 2 > width / 2; position = if (maxY > gap) ((dragY - gap) / (maxY - gap)).coerceIn(0f, 1f) else 0f; placement.edit().putBoolean("orb-right", right).putFloat("orb-position", position).apply(); dragging = false },
                    onDragCancel = { dragging = false }) { change, amount -> change.consume(); dragX = (dragX + amount.x).coerceIn(gap, maxX); dragY = (dragY + amount.y).coerceIn(gap, maxY) }
            }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) { drawCircle(orbInk.copy(alpha = 0.3f), style = Stroke(width = 1.dp.toPx())) }
            if (expanded) Canvas(Modifier.size(20.dp)) {
                drawLine(orbInk, Offset(size.width * .2f, size.height * .2f), Offset(size.width * .8f, size.height * .8f), 2.dp.toPx(), StrokeCap.Round)
                drawLine(orbInk, Offset(size.width * .8f, size.height * .2f), Offset(size.width * .2f, size.height * .8f), 2.dp.toPx(), StrokeCap.Round)
            } else WorkspaceGlyph(selected, orbInk, Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun WorkspaceGlyph(page: Int, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        fun p(x: Float, y: Float) = Offset(size.width * x, size.height * y)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, p(x1, y1), p(x2, y2), stroke, StrokeCap.Round)
        when (page) {
            0 -> { line(.34f,.24f,.13f,.5f); line(.13f,.5f,.34f,.76f); line(.66f,.24f,.87f,.5f); line(.87f,.5f,.66f,.76f); line(.57f,.17f,.43f,.83f) }
            1 -> { line(.19f,.27f,.43f,.5f); line(.43f,.5f,.19f,.73f); line(.52f,.73f,.81f,.73f) }
            2 -> { drawRoundRect(tint,p(.1f,.15f),Size(size.width*.8f,size.height*.57f),CornerRadius(stroke),style=Stroke(stroke)); line(.5f,.73f,.5f,.88f); line(.3f,.88f,.7f,.88f) }
            3 -> { val path=Path().apply { moveTo(size.width*.12f,size.height*.24f); lineTo(size.width*.43f,size.height*.24f); lineTo(size.width*.54f,size.height*.36f); lineTo(size.width*.88f,size.height*.36f); lineTo(size.width*.88f,size.height*.8f); lineTo(size.width*.12f,size.height*.8f); close() }; drawPath(path,tint,style=Stroke(stroke)); line(.14f,.48f,.86f,.48f) }
            else -> { line(.16f,.25f,.84f,.25f); line(.16f,.5f,.84f,.5f); line(.16f,.75f,.84f,.75f); drawCircle(tint,stroke*1.7f,p(.36f,.25f)); drawCircle(tint,stroke*1.7f,p(.67f,.5f)); drawCircle(tint,stroke*1.7f,p(.44f,.75f)) }
        }
    }
}
