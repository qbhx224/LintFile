package io.github.lumkit.io

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
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

    @Volatile
    var ioMode: IoModel = IoModel.NORMAL

    @Volatile
    var useSaf: Boolean = false

    /**
     * SAF 授权树 URI,由 [takePersistableUriPermission] 在授权成功后记录。
     * 所有 SAF 路径解析均以此作为授权根,支持任意授权子树。
     */
    @Volatile
    var safTreeUri: Uri? = null
        internal set

    private val shizukuListener = ShizukuUtil.onRequestPermissionResultListener
    private var listenerRegistered = false

    fun init(context: Context, fileConfig: LintFileConfig? = null) {
        this.context = context.applicationContext
        fileConfig?.let {
            this.ioMode = it.ioModel
        }
        if (!listenerRegistered) {
            ShizukuUtil.addListener(shizukuListener)
            listenerRegistered = true
        }
    }

    fun destroy() {
        if (listenerRegistered) {
            ShizukuUtil.removeListener(shizukuListener)
            listenerRegistered = false
        }
    }

}
