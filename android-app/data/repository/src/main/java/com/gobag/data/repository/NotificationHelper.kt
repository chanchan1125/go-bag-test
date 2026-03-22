package com.gobag.data.repository

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gobag.core.model.AlertModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object NotificationHelper {
    private const val CHANNEL_ID = "gobag_alerts"

    fun notify_alerts(context: Context, alerts: List<AlertModel>) {
        if (alerts.isEmpty()) return
        create_channel(context)
        val notificationManager = NotificationManagerCompat.from(context)
        if (!notificationManager.areNotificationsEnabled()) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        alerts.groupBy { it.bag_id }.forEach { (bag_id, bag_alerts) ->
            val expired = bag_alerts.count { it.type == "expired" }
            val soon = bag_alerts.count { it.type == "expiring_soon" }
            val bagName = bag_alerts.firstOrNull()?.bag_name?.ifBlank { bag_id } ?: bag_id
            val summary = "$expired expired, $soon expiring soon"
            val detailLines = bag_alerts
                .sortedWith(compareBy<AlertModel> { if (it.type == "expired") 0 else 1 }.thenBy { it.expiry_date_ms ?: Long.MAX_VALUE })
                .take(4)
                .map(::format_alert_line)
            val style = NotificationCompat.InboxStyle().also { inbox ->
                detailLines.forEach(inbox::addLine)
                if (bag_alerts.size > detailLines.size) {
                    inbox.setSummaryText("+${bag_alerts.size - detailLines.size} more item(s)")
                } else {
                    inbox.setSummaryText(summary)
                }
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Expiry alerts for $bagName")
                .setContentText(detailLines.firstOrNull() ?: summary)
                .setSubText(summary)
                .setStyle(style)
                .setAutoCancel(true)
                .build()
            try {
                notificationManager.notify(bag_id.hashCode(), notification)
            } catch (_: SecurityException) {
                return
            }
        }
    }

    private fun create_channel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Go-Bag Alerts", NotificationManager.IMPORTANCE_DEFAULT)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun format_alert_line(alert: AlertModel): String {
        val status = if (alert.type == "expired") "Expired" else "Expiring soon"
        val date = format_expiry_date(alert.expiry_date_ms)
        return "${alert.item_name.ifBlank { alert.item_id }} | $status | $date"
    }

    private fun format_expiry_date(value: Long?): String {
        if (value == null) return "No date"
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return formatter.format(Date(value))
    }
}
