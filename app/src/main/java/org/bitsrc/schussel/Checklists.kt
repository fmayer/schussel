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

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

data class ChecklistItem(val id: String, val text: String, val checked: Boolean)

data class Checklist(
    val id: String,
    val name: String,
    val items: List<ChecklistItem>,
    val devices: List<BtDevice>,
    /** Daily auto-uncheck time as minutes past midnight; null = never reset. */
    val resetMinutes: Int? = null,
    /** Epoch millis of the most recent reset that has been applied. */
    val lastResetAt: Long = 0L,
    /** When true, the reminder sounds a repeating alarm until dismissed. */
    val alarm: Boolean = false,
) {
    val doneCount: Int get() = items.count { it.checked }
    val anyUnchecked: Boolean get() = items.any { !it.checked }

    /** What to show as the list's heading: its name, else its devices, else a fallback. */
    val label: String
        get() = name.ifBlank {
            devices.joinToString(", ") { it.name }.ifBlank { "Untitled list" }
        }
}

/**
 * All of the user's checklists, persisted as a single JSON array in
 * SharedPreferences. Each list owns its own trigger devices, e.g.
 *
 *   [{"id":"…","name":"Leaving the car",
 *     "items":[{"id":"…","text":"Lights off","checked":false}],
 *     "devices":[{"address":"AA:BB:…","name":"My Car"}]}]
 */
object Checklists {
    private const val PREFS = "checklists"
    private const val KEY = "lists_json"

    fun all(context: Context): List<Checklist> {
        val json = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            parseLists(JSONArray(json))
        } catch (_: JSONException) {
            emptyList()
        }
    }

    fun get(context: Context, listId: String): Checklist? =
        all(context).firstOrNull { it.id == listId }

    /** Lists whose trigger devices include [address]. */
    fun listsForDevice(context: Context, address: String): List<Checklist> =
        all(context).filter { list -> list.devices.any { it.address == address } }

    fun addList(context: Context): String {
        val id = UUID.randomUUID().toString()
        save(context, all(context) + Checklist(id, "", emptyList(), emptyList()))
        return id
    }

    fun removeList(context: Context, listId: String) {
        save(context, all(context).filterNot { it.id == listId })
    }

    /** Swap a list with its neighbour above (up = true) or below. */
    fun moveList(context: Context, listId: String, up: Boolean) {
        val current = all(context).toMutableList()
        val i = current.indexOfFirst { it.id == listId }
        if (i < 0) return
        val j = if (up) i - 1 else i + 1
        if (j !in current.indices) return
        current[i] = current[j].also { current[j] = current[i] }
        save(context, current)
    }

    fun rename(context: Context, listId: String, name: String) =
        update(context, listId) { it.copy(name = name.trim()) }

    fun addItem(context: Context, listId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        update(context, listId) {
            it.copy(items = it.items + ChecklistItem(UUID.randomUUID().toString(), trimmed, false))
        }
    }

    fun removeItem(context: Context, listId: String, itemId: String) =
        update(context, listId) { it.copy(items = it.items.filterNot { i -> i.id == itemId }) }

    fun setChecked(context: Context, listId: String, itemId: String, checked: Boolean) =
        update(context, listId) {
            it.copy(items = it.items.map { i -> if (i.id == itemId) i.copy(checked = checked) else i })
        }

    /** Tick (checked = true) or untick every item in a list. */
    fun setAllChecked(context: Context, listId: String, checked: Boolean) =
        update(context, listId) {
            it.copy(items = it.items.map { i -> i.copy(checked = checked) })
        }

    fun addDevice(context: Context, listId: String, device: BtDevice) =
        update(context, listId) {
            it.copy(devices = it.devices.filterNot { d -> d.address == device.address } + device)
        }

    fun removeDevice(context: Context, listId: String, address: String) =
        update(context, listId) {
            it.copy(devices = it.devices.filterNot { d -> d.address == address })
        }

    /**
     * Set (or clear, with null) a list's daily reset time in minutes past midnight.
     * Anchors [Checklist.lastResetAt] to now so enabling it doesn't immediately fire
     * for a time that already passed earlier today.
     */
    fun setResetTime(context: Context, listId: String, minutes: Int?) =
        update(context, listId) {
            it.copy(
                resetMinutes = minutes,
                lastResetAt = if (minutes == null) 0L else System.currentTimeMillis(),
            )
        }

    fun setAlarm(context: Context, listId: String, alarm: Boolean) =
        update(context, listId) { it.copy(alarm = alarm) }

    /**
     * Apply any reset that has come due since it was last applied. Lazy — call this
     * before reading/evaluating lists (on app open and on device connect). Cheap and
     * a no-op when nothing is due.
     */
    fun applyDueResets(context: Context) {
        val now = System.currentTimeMillis()
        var changed = false
        val updated = all(context).map { list ->
            val minutes = list.resetMinutes ?: return@map list
            val scheduled = mostRecentReset(now, minutes)
            if (list.lastResetAt < scheduled) {
                changed = true
                list.copy(
                    items = list.items.map { it.copy(checked = false) },
                    lastResetAt = scheduled,
                )
            } else {
                list
            }
        }
        if (changed) save(context, updated)
    }

    /** The most recent occurrence of [minutes]-past-midnight at or before [now]. */
    private fun mostRecentReset(now: Long, minutes: Int): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis > now) cal.add(Calendar.DAY_OF_MONTH, -1)
        return cal.timeInMillis
    }

    private fun update(context: Context, listId: String, transform: (Checklist) -> Checklist) {
        save(context, all(context).map { if (it.id == listId) transform(it) else it })
    }

    // --- (de)serialization ---

    private fun parseLists(arr: JSONArray): List<Checklist> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            add(
                Checklist(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", "Untitled"),
                    items = parseItems(o.optJSONArray("items")),
                    devices = parseDevices(o.optJSONArray("devices")),
                    resetMinutes = o.optInt("resetMinutes", -1).let { if (it < 0) null else it },
                    lastResetAt = o.optLong("lastResetAt", 0L),
                    alarm = o.optBoolean("alarm", false),
                )
            )
        }
    }

    private fun parseItems(arr: JSONArray?): List<ChecklistItem> {
        arr ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    ChecklistItem(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        text = o.getString("text"),
                        checked = o.optBoolean("checked", false),
                    )
                )
            }
        }
    }

    private fun parseDevices(arr: JSONArray?): List<BtDevice> {
        arr ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val address = o.getString("address")
                add(BtDevice(address, o.optString("name", address)))
            }
        }
    }

    private fun save(context: Context, lists: List<Checklist>) {
        val arr = JSONArray()
        for (list in lists) {
            val items = JSONArray()
            for (item in list.items) {
                items.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("text", item.text)
                        .put("checked", item.checked)
                )
            }
            val devices = JSONArray()
            for (d in list.devices) {
                devices.put(JSONObject().put("address", d.address).put("name", d.name))
            }
            arr.put(
                JSONObject()
                    .put("id", list.id)
                    .put("name", list.name)
                    .put("items", items)
                    .put("devices", devices)
                    .put("resetMinutes", list.resetMinutes ?: -1)
                    .put("lastResetAt", list.lastResetAt)
                    .put("alarm", list.alarm)
            )
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
