package dev.n2k02.localadb

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.UUID

internal object LocalHelperDiagnostics {
    data class Status(val running: Boolean = false, val message: String = "LocalHelper not tested")
    val state = MutableStateFlow(Status())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    fun start(context: Context) {
        // A cancelled test must finish unbinding before another uses the same service tag.
        if (job?.isCompleted == false) return
        state.value = Status(running = true, message = "Connecting LocalHelper…")
        job = scope.launch {
            try {
                check(Shizuku.pingBinder() && Shizuku.getUid() == 2000) {
                    "Shizuku shell Binder is unavailable"
                }
                val message = LocalHelperProbe.run(
                    UserServiceConnection(context.applicationContext), UUID.randomUUID().toString())
                state.value = Status(message = message)
            } catch (cancelled: CancellationException) {
                state.value = Status(message = "LocalHelper test cancelled")
                throw cancelled
            } catch (failure: Exception) {
                state.value = Status(message = "LocalHelper failed: ${failure.message}")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        state.value = Status()
    }

    private class UserServiceConnection(context: Context) : LocalHelperProbe.Connection {
        private val connected = CompletableDeferred<IBinder>()
        private var binder: IBinder? = null
        private var bindingRequested = false
        private val args = Shizuku.UserServiceArgs(ComponentName(context, LocalHelperService::class.java))
            .tag("localhelper-connection-test")
            .version(1)
            .processNameSuffix("localhelper_test")
            .daemon(false)
        private val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null || !service.pingBinder()) {
                    connected.completeExceptionally(IllegalStateException("Helper Binder is dead"))
                } else {
                    binder = service
                    connected.complete(service)
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
                connected.completeExceptionally(IllegalStateException("Helper disconnected"))
            }
        }

        override suspend fun connect() {
            bindingRequested = true
            Shizuku.bindUserService(args, callback)
            binder = connected.await()
        }

        override suspend fun ping(nonce: String): LocalHelperProbe.Reply = withContext(Dispatchers.IO) {
            val remote = checkNotNull(binder) { "Helper disconnected" }
            check(remote.pingBinder()) { "Helper Binder is dead" }
            val request = Parcel.obtain()
            val response = Parcel.obtain()
            try {
                request.writeInterfaceToken(LocalHelperService.DESCRIPTOR)
                request.writeString(nonce)
                check(remote.transact(LocalHelperService.PING, request, response, 0)) {
                    "Helper rejected the test transaction"
                }
                response.readException()
                LocalHelperProbe.Reply(response.readInt(), response.readString().orEmpty())
            } finally {
                response.recycle()
                request.recycle()
            }
        }

        override suspend fun close() {
            if (bindingRequested) Shizuku.unbindUserService(args, callback, true)
            binder = null
        }
    }
}
