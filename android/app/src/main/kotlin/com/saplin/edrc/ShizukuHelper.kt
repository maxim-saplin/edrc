package com.saplin.edrc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object ShizukuHelper {
    const val PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_PERMISSION = 1401
    const val USER_SERVICE_VERSION = 2

    private val serviceRef = AtomicReference<IDumpService?>()
    private val bindLatch = AtomicReference<CountDownLatch?>(null)
    private var connection: ServiceConnection? = null
    private val initialized = AtomicBoolean(false)
    private val autoAsked = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(Map<String, Any>) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        maybeAskPermission()
        emit()
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        serviceRef.set(null)
        emit()
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        emit()
        mainHandler.postDelayed({ emit() }, 300)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!initialized.compareAndSet(false, true)) {
            emit()
            return
        }
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        maybeAskPermission()
    }

    fun addStatusListener(listener: (Map<String, Any>) -> Unit) {
        listeners.add(listener)
        appContext?.let { listener(status(it)) }
    }

    fun removeStatusListener(listener: (Map<String, Any>) -> Unit) {
        listeners.remove(listener)
    }

    fun status(context: Context): Map<String, Any> {
        val installed = isInstalled(context)
        val running = installed && Shizuku.pingBinder()
        val permission = running &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        return mapOf(
            "installed" to installed,
            "running" to running,
            "permissionGranted" to permission,
            "ready" to permission,
        )
    }

    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun openShizuku(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        }
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder()) {
            appContext?.let { openShizuku(it) }
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            emit()
            return
        }
        Shizuku.requestPermission(REQUEST_PERMISSION)
    }

    fun collect(context: Context, force: Boolean): Map<String, Any?> {
        val service = ensureService(context)
            ?: throw IllegalStateException("Shizuku dump service not bound")
        val json = service.collectFrame(force)
        return FrameStore.jsonToChannelMap(json)
    }

    fun maybeAskPermission() {
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return
        if (!autoAsked.compareAndSet(false, true)) return
        Shizuku.requestPermission(REQUEST_PERMISSION)
    }

    private fun emit() {
        val ctx = appContext ?: return
        val snapshot = status(ctx)
        mainHandler.post {
            listeners.forEach { it(snapshot) }
        }
    }

    @Synchronized
    private fun ensureService(context: Context): IDumpService? {
        serviceRef.get()?.let { return it }
        if (!Shizuku.pingBinder()) return null
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return null

        val latch = CountDownLatch(1)
        bindLatch.set(latch)
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, DumpService::class.java.name),
        )
            .daemon(true)
            .processNameSuffix("dump")
            .debuggable(false)
            .tag("edrc-dump")
            .version(USER_SERVICE_VERSION)

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) {
                    serviceRef.set(IDumpService.Stub.asInterface(binder))
                }
                bindLatch.get()?.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceRef.set(null)
            }
        }
        connection = conn
        Shizuku.bindUserService(args, conn)
        latch.await(15, TimeUnit.SECONDS)
        return serviceRef.get()
            ?: throw IllegalStateException("Shizuku dump service not bound")
    }
}
