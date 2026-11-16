package `in`.xprice.tracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import `in`.xprice.tracker.ui.LootwiseApp
import `in`.xprice.tracker.ui.theme.LootwiseTheme
import `in`.xprice.tracker.worker.PriceAlertWorker
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        scheduleBackgroundPriceCheck()
        requestNotificationPermissionIfNeeded()
        setContent { LootwiseTheme { LootwiseApp() } }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun scheduleBackgroundPriceCheck() {
        val request = PeriodicWorkRequestBuilder<PriceAlertWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "rupeevault_x_background_sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
