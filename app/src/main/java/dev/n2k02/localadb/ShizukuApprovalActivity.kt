package dev.n2k02.localadb

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.n2k02.theme.N2K02Theme
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants

/** Protected Shizuku server permission prompt, not an app-callable grant API. */
class ShizukuApprovalActivity : ComponentActivity() {
    private var answered = false
    private var requestUid = -1
    private var requestPid = -1
    private var requestCode = -1

    private fun respond(allow: Boolean, permanent: Boolean) {
        if (answered) return
        answered = true
        val bundle = Bundle().apply {
            putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, allow)
            putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME, !permanent)
        }
        runCatching {
            if (Shizuku.pingBinder()) {
                Shizuku.dispatchPermissionConfirmationResult(
                    requestUid, requestPid, requestCode, bundle)
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = packageName + ".intent.action.REQUEST_PERMISSION"
        if (intent.action != action) {
            finish()
            return
        }
        requestUid = intent.getIntExtra("uid", -1)
        requestPid = intent.getIntExtra("pid", -1)
        requestCode = intent.getIntExtra("requestCode", -1)
        val application = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("applicationInfo", ApplicationInfo::class.java)
        else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<ApplicationInfo>("applicationInfo")
        }
        if (requestUid < 0 || requestPid <= 0 || requestCode < 0 ||
            application == null || application.uid != requestUid ||
            packageManager.getPackagesForUid(requestUid)?.contains(application.packageName) != true ||
            !Shizuku.pingBinder() ||
            !NativeShizukuManager.ownerMatches(
                NativeShizukuManager.managerPermissionOwner(this), packageName)
        ) {
            finish()
            return
        }
        val label = runCatching { application.loadLabel(packageManager).toString() }
            .getOrDefault(application.packageName)
        setContent {
            N2K02Theme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AlertDialog(
                        onDismissRequest = { respond(false, false) },
                        title = { Text("Allow Shizuku API access?") },
                        text = { Text(
                            "$label (" + application.packageName + ") is requesting privileged " +
                            "Shizuku API access with Android shell UID 2000. " +
                            "Only grant this to an app you trust."
                        ) },
                        confirmButton = {
                            TextButton(onClick = { respond(true, true) }) {
                                Text("Allow")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { respond(false, false) }) {
                                Text("Deny")
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (!answered && requestUid >= 0 && requestPid > 0 && requestCode >= 0) {
            respond(false, false)
        }
        super.onDestroy()
    }
}
