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

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Registered in the manifest so the system can deliver ACL_CONNECTED even when
 * no part of the app is running. On connect, remind the user only if a checklist
 * tied to this device still has unticked items.
 */
class BtConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device = deviceOf(intent) ?: return
        val address = device.address ?: return

        // Clear any list whose daily reset came due, so stale ticks don't hide a reminder.
        Checklists.applyDueResets(context)

        // Lists that include this device AND still have something unticked.
        val pending = Checklists.listsForDevice(context, address).filter { it.anyUnchecked }
        if (pending.isEmpty()) return

        Notifications.showReminder(context, pending)
    }

    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
