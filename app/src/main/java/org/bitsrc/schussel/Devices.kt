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
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class BtDevice(val address: String, val name: String)

/** Bonded (paired) devices reported by the system. */
object PairedDevices {

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    /** Empty if the permission is missing or Bluetooth is off/unavailable. */
    fun list(context: Context): List<BtDevice> {
        if (!hasPermission(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty().map {
                BtDevice(it.address, runCatching { it.name }.getOrNull() ?: it.address)
            }.sortedBy { it.name.lowercase() }
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}
