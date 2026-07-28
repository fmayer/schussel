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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    private const val CHANNEL_ID = "bt_connect"
    private const val ALARM_CHANNEL_ID = "bt_alarm"

    // Stable id so a fresh reminder replaces the previous one instead of stacking.
    private const val NOTIF_ID = 1

    /** Idempotent — safe to call from a cold BroadcastReceiver before notifying. */
    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)

        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Checklist reminders",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Reminds you of unticked items when your device connects" }
            )
        }

        if (mgr.getNotificationChannel(ALARM_CHANNEL_ID) == null) {
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            mgr.createNotificationChannel(
                NotificationChannel(
                    ALARM_CHANNEL_ID,
                    "Alarm reminders",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Sounds an alarm that repeats until you dismiss it"
                    setSound(alarmSound, attrs)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
                    enableLights(true)
                }
            )
        }
    }

    /** [lists] must be non-empty and each is expected to have unticked items. */
    fun showReminder(context: Context, lists: List<Checklist>) {
        val pending = lists.filter { it.anyUnchecked }
        if (pending.isEmpty()) return
        ensureChannel(context)

        // Escalate to the insistent alarm if any pending list opted into it.
        val alarm = pending.any { it.alarm }
        val channelId = if (alarm) ALARM_CHANNEL_ID else CHANNEL_ID

        val title: String
        val summary: String
        val bigText: String
        if (pending.size == 1) {
            val list = pending.first()
            val todo = list.items.filter { !it.checked }
            title = list.label
            summary = plural(todo.size)
            bigText = todo.joinToString("\n") { "• ${it.text}" }
        } else {
            title = "Checklist reminders"
            summary = pending.joinToString(", ") { "${it.label} (${it.items.count { i -> !i.checked }})" }
            bigText = pending.joinToString("\n\n") { list ->
                list.label + "\n" +
                    list.items.filter { !it.checked }.joinToString("\n") { "• ${it.text}" }
            }
        }

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        if (alarm) {
            builder.setCategory(NotificationCompat.CATEGORY_ALARM)
        }

        val notification = builder.build()
        if (alarm) {
            // Repeat the alarm sound/vibration until the notification is dismissed or opened.
            notification.flags = notification.flags or Notification.FLAG_INSISTENT
        }

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted yet — nothing we can do here.
        }
    }

    private fun plural(n: Int): String = if (n == 1) "1 item left" else "$n items left"
}
