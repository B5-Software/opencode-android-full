package io.github.opencodeandroid.preview

import io.github.opencodeandroid.i18n.rememberNativeStrings
import io.github.opencodeandroid.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable fun PreviewLauncher() {
    val strings = rememberNativeStrings()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var show by rememberSaveable { mutableStateOf(false) }
    var port by rememberSaveable { mutableStateOf(context.getSharedPreferences("project-preview", 0).getInt("port", 3000).toString()) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    OutlinedButton({ show = true; error = null }) { Text(strings.text(R.string.native_6e7408950cf5)) }
    if (show) AlertDialog(onDismissRequest = { if (!checking) show = false }, title = { Text(strings.text(R.string.native_bfb4951e071d)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.text(R.string.native_f80652913d06))
            OutlinedTextField(port, { if (it.length <= 5 && it.all(Char::isDigit)) { port = it; error = null } }, label = { Text(strings.text(R.string.native_2c41e85af61f)) },
                enabled = !checking, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = !checking, onClick = {
            val target = runCatching { PreviewTarget.forPort(port.toInt()) }.getOrNull()
            if (target == null) { error = strings.text(R.string.native_77c3a828a5c5); return@TextButton }
            scope.launch {
                checking = true
                val listening = target.isListening()
                checking = false
                if (listening) {
                    context.getSharedPreferences("project-preview", 0).edit().putInt("port", target.port).apply()
                    context.startActivity(PreviewActivity.intent(context, target.port)); show = false
                } else error = strings.text(R.string.native_7311a9dfea51)
            }
        }) { Text(if (checking) strings.text(R.string.native_4f8d84105475) else strings.text(R.string.native_bbdb6043b5e9)) }
    }, dismissButton = { TextButton({ show = false }, enabled = !checking) { Text(strings.text(R.string.native_0eddf4ad9b94)) } })
}
