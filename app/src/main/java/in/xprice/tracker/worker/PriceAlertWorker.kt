package `in`.xprice.tracker.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import `in`.xprice.tracker.data.PriceHistoryStorage
import `in`.xprice.tracker.data.XboxStoreRepository
import `in`.xprice.tracker.widget.RupeeVaultWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PriceAlertWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val storage = PriceHistoryStorage(applicationContext)
            val activeAlerts = storage.getAllAlerts()
            if (activeAlerts.isNotEmpty()) {
                val repository = XboxStoreRepository(applicationContext)
                val liveDeals = repository.fetchCatalogByBigIdsSync(activeAlerts.keys.toList())

                liveDeals.forEach { deal ->
                    val alert = activeAlerts[deal.productId]
                    if (alert != null && deal.currentPriceInr in 1..alert.targetPriceInr) {
                        sendPriceDropNotification(
                            applicationContext,
                            deal.productId,
                            deal.title,
                            deal.currentPriceInr,
                            alert.targetPriceInr,
                            deal.storeUrl
                        )
                    }
                }
            }
            try { RupeeVaultWidget().updateAll(applicationContext) } catch (_: Exception) {}
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
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
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "RupeeVault X Price Drop Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                )
                manager.createNotificationChannel(channel)
            }

            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(storeUrl))
            val pendingIntent = PendingIntent.getActivity(
                context,
                productId.hashCode(),
                viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("🔥 Price Drop Alert: $title")
                .setContentText("Now ₹$currentInr on Xbox India Store (Target: ₹$targetInr)! Tap to buy.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            manager.notify(productId.hashCode(), notification)
        }
    }
}
