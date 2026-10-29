package io.github.opencodeandroid

import io.github.opencodeandroid.i18n.rememberNativeStrings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable internal fun WebViewRenderingControls() {
    val strings = rememberNativeStrings()
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var balanced by remember { mutableStateOf(WebViewRendering.balanced(context)) }
    TextButton({ open = true }) { Text(strings.text(R.string.native_179a57921f91)) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(strings.text(R.string.native_3024ec603eb7)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(true to strings.text(R.string.native_55b6d7f645f3), false to strings.text(R.string.native_66f8bafa9f7f)).forEach { (value, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(balanced == value, { balanced = value; WebViewRendering.setBalanced(context, value) })
                    Text(label)
                }
            }
            Text(strings.text(R.string.native_d4ecf38c341d), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton({ open = false }) { Text(strings.text(R.string.native_7a129a94c6ff)) } })
}
