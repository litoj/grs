package cz.litoj.grs.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import cz.litoj.grs.R

/**
 * Searchable dialog for picking an installed app to auto-open when coordinates are mocked.
 *
 * The selected app's [ComponentName] is persisted in SharedPreferences ("grs_settings" /
 * "target_app") and passed to [onAppSelected].
 *
 * @param onAppSelected Called with the selected [ComponentName], or null when the user
 *   clears the selection.
 */
@Composable
fun AppPickerDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onAppSelected: (ComponentName?) -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("grs_settings", Context.MODE_PRIVATE) }
    var searchQuery by remember { mutableStateOf("") }

    val pm = context.packageManager
    val installedApps = remember {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        pm.queryIntentActivities(intent, 0)
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
    }
    val filteredApps = remember(searchQuery) {
        installedApps.filter {
            it.loadLabel(pm).contains(searchQuery, ignoreCase = true)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.search_apps)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    // Clear selection option
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.clear_app_selection)) },
                            modifier = Modifier.clickable {
                                prefs.edit { remove("target_app") }
                                onAppSelected(null)
                            },
                        )
                        HorizontalDivider()
                    }
                    items(filteredApps, key = { it.activityInfo.packageName }) { app ->
                        ListItem(
                            headlineContent = { Text(app.loadLabel(pm).toString()) },
                            leadingContent = {
                                val icon = remember(app.activityInfo.packageName) {
                                    app.loadIcon(pm)
                                }
                                Image(
                                    bitmap = icon.toBitmap().asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                )
                            },
                            modifier = Modifier.clickable {
                                val component = ComponentName(
                                    app.activityInfo.packageName,
                                    app.activityInfo.name,
                                )
                                prefs.edit { putString("target_app", "${component.packageName}/${component.className}") }
                                onAppSelected(component)
                            },
                        )
                    }
                }
            }
        }
    }
}
