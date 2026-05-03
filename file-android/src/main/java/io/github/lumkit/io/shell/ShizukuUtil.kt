package io.github.lumkit.io.shell

import android.content.pm.PackageManager
import android.os.RemoteException
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener


object ShizukuUtil {

    const val REQUEST_CODE = 0x000001

    val onRequestPermissionResultListener by lazy {
        OnRequestPermissionResultListener { _, _ ->
            checkPermission()
        }
    }

    fun addListener(onRequestPermissionResultListener: OnRequestPermissionResultListener) {
        Shizuku.addRequestPermissionResultListener(onRequestPermissionResultListener)
    }

    fun removeListener(onRequestPermissionResultListener: OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(onRequestPermissionResultListener)
    }

    fun checkPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: RemoteException) {
        e.printStackTrace()
        false
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder() && checkPermission()
    } catch (e: Exception) {
        false
    }

    fun runCmd(cmd: String): String? = try {
        AdbShellPublic.doCmdSync(cmd)
    } catch (e: ShellException) {
        e.printStackTrace()
        null
    }

    fun requestPermission() {
        Shizuku.requestPermission(REQUEST_CODE)
    }
}
