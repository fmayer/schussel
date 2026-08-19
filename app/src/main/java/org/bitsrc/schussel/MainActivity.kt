/*
 * Copyright 2026 Florian Mayer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitsrc.schussel

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.bitsrc.schussel.ui.theme.SchusselTheme
import java.util.Calendar

private sealed interface Nav {
    data object Main : Nav
    data object About : Nav
    data object Licenses : Nav
    data class Settings(val listId: String) : Nav
}

class MainActivity : ComponentActivity() {

    // Hoisted out of composition so onResume() and mutations can refresh it.
    private val lists = mutableStateListOf<Checklist>()

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.ensureChannel(this)
        requestNeededPermissions()
        enableEdgeToEdge()
        setContent {
            SchusselTheme {
                var nav by remember { mutableStateOf<Nav>(Nav.Main) }

                // System Back / gesture reverses in-app navigation instead of leaving the app.
                // TODO: this assumes only one level beyond main
                BackHandler(enabled = nav != Nav.Main) { nav = Nav.Main }

                when (val n = nav) {
                    Nav.Main -> MainScreen(
                        lists = lists,
                        onTick = { if (Checklists.hasDueReset(this)) reload() },
                        onAddList = { Checklists.addList(this); reload() },
                        onOpenAbout = { nav = Nav.About },
                        onOpenLicenses = { nav = Nav.Licenses },
                        onDeleteList = { Checklists.removeList(this, it); reload() },
                        onMove = { id, up -> Checklists.moveList(this, id, up); reload() },
                        onSetAllChecked = { id, checked ->
                            Checklists.setAllChecked(this, id, checked); reload()
                        },
                        onOpenSettings = { nav = Nav.Settings(it) },
                        onAddItem = { listId, text -> Checklists.addItem(this, listId, text); reload() },
                        onToggleItem = { listId, itemId, checked ->
                            Checklists.setChecked(this, listId, itemId, checked); reload()
                        },
                        onRemoveItem = { listId, itemId ->
                            Checklists.removeItem(this, listId, itemId); reload()
                        },
                    )

                    Nav.About -> AboutScreen(onBack = { nav = Nav.Main })

                    Nav.Licenses -> LicensesScreen(onBack = { nav = Nav.Main })

                    is Nav.Settings -> {
                        val list = lists.firstOrNull { it.id == n.listId }
                        if (list == null) {
                            LaunchedEffect(Unit) { nav = Nav.Main }
                        } else {
                            ListSettingsScreen(
                                list = list,
                                onBack = { nav = Nav.Main },
                                onRename = { Checklists.rename(this, list.id, it); reload() },
                                onSetResetTime = { Checklists.setResetTime(this, list.id, it); reload() },
                                onSetAlarm = { Checklists.setAlarm(this, list.id, it); reload() },
                                onToggleDevice = { device ->
                                    if (list.devices.any { it.address == device.address }) {
                                        Checklists.removeDevice(this, list.id, device.address)
                                    } else {
                                        Checklists.addDevice(this, list.id, device)
                                    }
                                    reload()
                                },
                                onRequestPermission = ::requestNeededPermissions,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        Checklists.applyDueResets(this)
        lists.clear()
        lists.addAll(Checklists.all(this))
    }

    fun requestNeededPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
    }
}

@Composable
private fun ScreenScaffold(content: @Composable (Modifier) -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        content(
            Modifier
                .padding(padding)
                // Shrink content above the soft keyboard (edge-to-edge disables the
                // manifest's adjustResize, so the IME inset must be consumed here).
                .imePadding()
                .padding(16.dp)
                .fillMaxSize()
        )
    }
}

@Composable
private fun MainScreen(
    lists: List<Checklist>,
    onTick: () -> Unit,
    onAddList: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLicenses: () -> Unit,
    onDeleteList: (String) -> Unit,
    onMove: (String, Boolean) -> Unit,
    onSetAllChecked: (String, Boolean) -> Unit,
    onOpenSettings: (String) -> Unit,
    onAddItem: (String, String) -> Unit,
    onToggleItem: (String, String, Boolean) -> Unit,
    onRemoveItem: (String, String) -> Unit,
) {
    // Advances the reset countdown while the screen is open, and applies any reset that
    // comes due while the app sits idle in the foreground.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
            onTick()
        }
    }
    var appMenuOpen by remember { mutableStateOf(false) }

    ScreenScaffold { modifier ->
        Column(modifier) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Checklists", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onAddList) { Text("Add list") }
                    Box {
                        HeaderAction("⋮", "More options", onClick = { appMenuOpen = true })
                        DropdownMenu(
                            expanded = appMenuOpen,
                            onDismissRequest = { appMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("About") },
                                onClick = { appMenuOpen = false; onOpenAbout() },
                            )
                            DropdownMenuItem(
                                text = { Text("Open-source licenses") },
                                onClick = { appMenuOpen = false; onOpenLicenses() },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (lists.isEmpty()) {
                Text(
                    "No lists yet. Tap “Add list”, then add items and — in the list's " +
                        "settings — the Bluetooth device(s) that should trigger it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(lists, key = { _, it -> it.id }) { index, list ->
                        ListSection(
                            list = list,
                            now = now,
                            isFirst = index == 0,
                            isLast = index == lists.lastIndex,
                            onMoveUp = { onMove(list.id, true) },
                            onMoveDown = { onMove(list.id, false) },
                            onSetAllChecked = { onSetAllChecked(list.id, it) },
                            onOpenSettings = { onOpenSettings(list.id) },
                            onDelete = { onDeleteList(list.id) },
                            onAddItem = { onAddItem(list.id, it) },
                            onToggleItem = { itemId, checked -> onToggleItem(list.id, itemId, checked) },
                            onRemoveItem = { onRemoveItem(list.id, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListSection(
    list: Checklist,
    now: Long,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onSetAllChecked: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onDelete: () -> Unit,
    onAddItem: (String) -> Unit,
    onToggleItem: (String, Boolean) -> Unit,
    onRemoveItem: (String) -> Unit,
) {
    var newItem by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val subtitle = buildString {
        append("${list.doneCount}/${list.items.size} done")
        list.resetMinutes?.let { minutes ->
            append("  •  resets in ${formatCountdown(nextResetMillis(now, minutes) - now)}")
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete list?") },
            text = { Text("“${list.label}” and its ${list.items.size} item(s) will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        list.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
                if (!(isFirst && isLast)) {
                    HeaderAction("↑", "Move list up", enabled = !isFirst, onClick = onMoveUp)
                    HeaderAction("↓", "Move list down", enabled = !isLast, onClick = onMoveDown)
                }
                Box {
                    HeaderAction("⋮", "List options", onClick = { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Tick all") },
                            enabled = list.anyUnchecked,
                            onClick = { menuOpen = false; onSetAllChecked(true) },
                        )
                        DropdownMenuItem(
                            text = { Text("Untick all") },
                            enabled = list.doneCount > 0,
                            onClick = { menuOpen = false; onSetAllChecked(false) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = { menuOpen = false; onOpenSettings() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            list.items.forEach { item ->
                ChecklistRow(
                    item = item,
                    onToggle = { onToggleItem(item.id, !item.checked) },
                    onRemove = { onRemoveItem(item.id) },
                )
            }

            Row(
                Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newItem,
                    onValueChange = { newItem = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Add an item…") },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onAddItem(newItem); newItem = "" },
                    enabled = newItem.isNotBlank(),
                ) { Text("Add") }
            }
        }
    }
}

@Composable
private fun HeaderAction(
    label: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    ScreenScaffold { modifier ->
        Column(modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back") }
            }
            Text("About Schussel", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "“Schussel” is German for a scatterbrained, forgetful person — the one who drives " +
                    "off with the lights still on. This app is the antidote.",
                style = MaterialTheme.typography.bodyMedium,
            )

            AboutHeading("What it does")
            Text(
                "When your phone connects to a Bluetooth device you've chosen — usually your car — " +
                    "Schussel checks the linked checklist. If anything is still unticked, it sends a " +
                    "reminder naming exactly what you forgot. Tick everything off and it stays quiet.",
                style = MaterialTheme.typography.bodyMedium,
            )

            AboutHeading("Setting it up")
            AboutStep("1", "Tap “Add list” and add the things you tend to forget.")
            AboutStep("2", "Open the list's ⋮ menu → Settings and pick the Bluetooth device(s) that " +
                "should trigger it. You can also give the list a name.")
            AboutStep("3", "Done. Next time your phone connects to that device with items left " +
                "unticked, you'll get a reminder.")

            AboutHeading("Daily reset")
            Text(
                "In a list's settings you can set a time to automatically untick everything once a " +
                    "day, so yesterday's ticks don't hide today's reminder. Lists without a reset time " +
                    "stay as you left them — and you can untick a whole list any time from its ⋮ menu.",
                style = MaterialTheme.typography.bodyMedium,
            )

            AboutHeading("Privacy & permissions")
            Text(
                "Nearby devices (Bluetooth) lets Schussel see your paired devices and notice when one " +
                    "connects; Notifications lets it remind you. Everything stays on your phone — " +
                    "nothing is sent anywhere.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(24.dp))
            Text(
                "Licensed under the Apache License 2.0.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AboutHeading(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun AboutStep(num: String, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("$num.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val licenseText = remember {
        context.resources.openRawResource(R.raw.apache_2_0)
            .bufferedReader().use { it.readText() }
    }

    ScreenScaffold { modifier ->
        Column(modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back") }
            }
            Text("Open-source licenses", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "schussel itself is licensed under the Apache License 2.0. " +
                    "Copyright © 2026 Florian Mayer.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))

            Text("Bundled libraries", style = MaterialTheme.typography.titleMedium)
            Text(
                "The app builds on these open-source libraries, all under the Apache License 2.0:",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            LicenseEntry("Jetpack Compose & AndroidX", "Copyright The Android Open Source Project")
            LicenseEntry("Kotlin standard library", "Copyright JetBrains s.r.o. and Kotlin contributors")
            LicenseEntry("kotlinx.coroutines", "Copyright JetBrains s.r.o. and contributors")

            Spacer(Modifier.height(20.dp))
            Text("Apache License 2.0", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                licenseText,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun LicenseEntry(name: String, holder: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        Text(holder, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ChecklistRow(item: ChecklistItem, onToggle: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(
            item.text,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (item.checked) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRemove) {
            Text("✕", modifier = Modifier.semantics { contentDescription = "Remove item" })
        }
    }
}

@Composable
private fun ListSettingsScreen(
    list: Checklist,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onSetResetTime: (Int?) -> Unit,
    onSetAlarm: (Boolean) -> Unit,
    onToggleDevice: (BtDevice) -> Unit,
    onRequestPermission: () -> Unit,
) {
    val context = LocalContext.current
    var name by remember(list.id) { mutableStateOf(list.name) }

    // Bump to re-read paired devices (e.g. after granting permission or toggling BT).
    var refresh by remember { mutableIntStateOf(0) }
    val hasPermission = remember(refresh) { PairedDevices.hasPermission(context) }
    val paired = remember(refresh) { PairedDevices.list(context) }
    val selected = list.devices.map { it.address }.toSet()

    ScreenScaffold { modifier ->
        Column(modifier.verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("‹ Back") }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it; onRename(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Name (optional)") },
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))

            Text("Daily reset", style = MaterialTheme.typography.titleMedium)
            Text(
                "If set, all items uncheck at this time each day. Default: no reset.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    val current = list.resetMinutes ?: (8 * 60)
                    TimePickerDialog(
                        context,
                        { _, hour, minute -> onSetResetTime(hour * 60 + minute) },
                        current / 60,
                        current % 60,
                        true, // 24-hour view
                    ).show()
                }) {
                    Text(list.resetMinutes?.let { formatMinutes(it) } ?: "Off — tap to set")
                }
                if (list.resetMinutes != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onSetResetTime(null) }) { Text("Turn off") }
                }
            }
            Spacer(Modifier.height(16.dp))

            Text("Reminder", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Sound an alarm", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Repeats a loud alarm and vibrates until you dismiss it, instead of a " +
                            "single quiet notification.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = list.alarm,
                    onCheckedChange = { onSetAlarm(it) },
                    modifier = Modifier.semantics { contentDescription = "Sound an alarm" },
                )
            }
            Spacer(Modifier.height(16.dp))

            Text("Trigger devices", style = MaterialTheme.typography.titleMedium)
            Text(
                "Paired devices that should trigger this list. Select as many as you like.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            when {
                !hasPermission -> {
                    Text(
                        "Bluetooth permission is needed to list your paired devices.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onRequestPermission(); refresh++ }) {
                        Text("Grant permission")
                    }
                }

                paired.isEmpty() -> {
                    Text(
                        "No paired devices found. Make sure Bluetooth is on and your car " +
                            "is paired in Android's Bluetooth settings, then refresh.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { refresh++ }) { Text("Refresh") }
                }

                else -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${selected.size} selected",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        TextButton(onClick = { refresh++ }) { Text("Refresh") }
                    }
                    paired.forEach { device ->
                        DeviceRow(
                            device = device,
                            checked = device.address in selected,
                            onToggle = { onToggleDevice(device) },
                        )
                    }
                }
            }
        }
    }
}

private fun formatMinutes(minutes: Int): String =
    "%02d:%02d".format(minutes / 60, minutes % 60)

/** Next occurrence of [minutes]-past-midnight strictly after [now], in epoch millis. */
private fun nextResetMillis(now: Long, minutes: Int): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, minutes / 60)
        set(Calendar.MINUTE, minutes % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
    return cal.timeInMillis
}

/** Remaining millis as HH:MM, rounded up so it never shows 00:00 while counting. */
private fun formatCountdown(remainingMillis: Long): String {
    val totalMinutes = ((remainingMillis + 59_999) / 60_000).coerceAtLeast(0)
    return "%02d:%02d".format(totalMinutes / 60, totalMinutes % 60)
}

@Composable
private fun DeviceRow(device: BtDevice, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge)
            Text(device.address, style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider()
}
