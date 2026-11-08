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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
        state.value = Status(running = true, message = "Waiting for verified ADB UID 2000…")
        job = scope.launch {
            try {
                // Never bind a Shizuku user service while local ADB is still
                // pairing, reconnecting or advertising an unverified identity.
                val verified = withTimeoutOrNull(30_000L) {
                    AdbEngine.status.first { it.connected && AdbTransport.isConnected }
                } != null
                check(verified) { "ADB shell UID 2000 not verified within 30s" }
                state.value = Status(running = true, message = "Checking Shizuku UID 2000…")
                val managerReady = withTimeoutOrNull(30_000L) {
                    while (true) {
                        if (!AdbEngine.status.value.connected || !AdbTransport.isConnected) {
                            error("ADB disconnected before Shizuku became ready")
                        }
                        if (NativeShizukuManager.state.value.serviceReady &&
                            runCatching { Shizuku.pingBinder() && Shizuku.getUid() == 2000 }
                                .getOrDefault(false)) break
                        kotlinx.coroutines.delay(250L)
                    }
                    true
                } == true
                check(managerReady) { "Shizuku UID 2000 not ready within 30s" }
                check(AdbEngine.status.value.connected && AdbTransport.isConnected) {
                    "ADB disconnected before helper binding"
                }
                state.value = Status(running = true, message = "Connecting LocalHelper…")
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
        private var closed = false
        private val args = Shizuku.UserServiceArgs(ComponentName(context, LocalHelperService::class.java))
            .tag("localhelper-connection-test")
            .version(1)
            .processNameSuffix("localhelper_test")
            .daemon(false)
        private val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (closed) return
                if (service == null || !service.pingBinder()) {
                    connected.completeExceptionally(IllegalStateException("Helper Binder is dead"))
                } else {
                    binder = service
                    connected.complete(service)
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
                if (!closed) connected.completeExceptionally(IllegalStateException("Helper disconnected"))
            }
        }

        override suspend fun connect() {
            check(!closed && AdbEngine.status.value.connected && AdbTransport.isConnected) {
                "Verified ADB UID 2000 connection lost"
            }
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
            closed = true
            binder = null
            if (bindingRequested) {
                bindingRequested = false
                Shizuku.unbindUserService(args, callback, true)
            }
        }
    }
}
