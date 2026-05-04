package io.github.lumkit.io

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import io.github.lumkit.io.data.IoModel
import io.github.lumkit.io.data.LintFileConfig
import io.github.lumkit.io.shell.ShizukuUtil

class LintFileConfiguration {

    companion object {
        @SuppressLint("StaticFieldLeak")
        val instance = LintFileConfiguration()
    }

    internal lateinit var context: Context
    val isInitialized: Boolean get() = ::context.isInitialized
    var ioMode: IoModel = IoModel.NORMAL
    var useSaf: Boolean = false

    fun init(context: Activity, fileConfig: LintFileConfig? = null) {
        this.context = context.applicationContext
        fileConfig?.let {
            this.ioMode = it.ioModel
        }
        ShizukuUtil.addListener(ShizukuUtil.onRequestPermissionResultListener)
    }

    fun destroy() {
        ShizukuUtil.removeListener(ShizukuUtil.onRequestPermissionResultListener)
    }

}
