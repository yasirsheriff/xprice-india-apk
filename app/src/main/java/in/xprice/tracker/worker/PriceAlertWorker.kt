package `in`.xprice.tracker.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import `in`.xprice.tracker.data.PriceHistoryStorage
import `in`.xprice.tracker.data.XboxStoreRepository
import `in`.xprice.tracker.data.formatInr
import `in`.xprice.tracker.widget.RupeeVaultWidget

/**
 * Runs every ~12h. Pulls the shared daily history for tracked games (favourites, alerts, owned),
 * fetches their live prices (which also records today's point), then fires alerts.
 */
class PriceAlertWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        val storage = PriceHistoryStorage(applicationContext)
        val tracked = storage.trackedProductIds()
        if (tracked.isNotEmpty()) {
            val repository = XboxStoreRepository(applicationContext)
            repository.syncSharedHistory(tracked)
            val live = repository.fetchCatalogByBigIds(tracked.toList())
            val alerts = storage.getAllAlerts()
            live.forEach { deal ->
                val alert = alerts[deal.productId] ?: return@forEach
                if (deal.currentPriceInr in 1..alert.targetPriceInr && storage.shouldNotify(deal.productId, deal.currentPriceInr)) {
                    sendPriceDropNotification(
                        applicationContext, deal.productId, deal.title,
                        deal.currentPriceInr, alert.targetPriceInr, deal.storeUrl
                    )
                }
            }
        }
        try { RupeeVaultWidget().updateAll(applicationContext) } catch (_: Exception) {}
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        const val CHANNEL_ID = "rupeevault_drop_alerts"

        fun sendPriceDropNotification(
            context: Context,
            productId: String,
            title: String,
            currentInr: Int,
            targetInr: Int,
            storeUrl: String
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Price drop alerts", NotificationManager.IMPORTANCE_HIGH)
                )
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                productId.hashCode(),
                Intent(Intent.ACTION_VIEW, Uri.parse(storeUrl)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("Price drop: $title")
                .setContentText("Now ${formatInr(currentInr)} on Xbox Store India (your target: ${formatInr(targetInr)}). Tap to buy.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            manager.notify(productId.hashCode(), notification)
        }
    }
}
