package dev.n2k02.localadb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** WorkManager persists a low-frequency recovery opportunity across process death. */
class AdbRecoveryWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!AdbSettings.backgroundRecoveryAllowed(app)) return Result.success()
        if (inputData.getBoolean("boot", false)) {
            if (!AdbSettings.get(app, AdbSettings.Option.START_ON_BOOT)) return Result.success()
            // Give the Android debugging stack time to initialize after unlock.
            delay(3_000)
            AdbEngine.restoreAfterReboot(app)
        }
        if (AdbSettings.get(app, AdbSettings.Option.WATCHDOG)) AdbEngine.start(app)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "localadb-watchdog"
        private const val BOOT = "localadb-boot"
        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            if (AdbSettings.get(context, AdbSettings.Option.WATCHDOG) &&
                !AdbSettings.stopped(context)) {
                manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<AdbRecoveryWorker>(15, TimeUnit.MINUTES).build())
            } else manager.cancelUniqueWork(PERIODIC)
        }
        fun onBoot(context: Context) {
            if (!AdbSettings.get(context, AdbSettings.Option.START_ON_BOOT) ||
                AdbSettings.stopped(context)) return
            val data = androidx.work.Data.Builder().putBoolean("boot", true).build()
            WorkManager.getInstance(context).enqueueUniqueWork(BOOT, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AdbRecoveryWorker>().setInputData(data)
                    .setInitialDelay(15, TimeUnit.SECONDS).build())
        }
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(BOOT)
        }
    }
}

class AdbBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            AdbRecoveryWorker.onBoot(context.applicationContext)
    }
}
