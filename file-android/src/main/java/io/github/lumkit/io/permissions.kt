package io.github.lumkit.io

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.app.ActivityCompat
import io.github.lumkit.io.data.IoModel
import io.github.lumkit.io.data.PermissionType
import io.github.lumkit.io.shell.ShizukuUtil
import rikka.shizuku.Shizuku

/**
 * 文件权限安全作用域
 */
fun LintFile.use(
    onRequestPermission: (PermissionType) -> Unit = {},
    granted: LintFile.() -> Unit
) {
    val instance = LintFileConfiguration.instance
    val context = instance.context
    when (instance.ioMode) {
        IoModel.SHIZUKU -> {
            if (ShizukuUtil.checkPermission() && Shizuku.pingBinder()) {
                granted()
            } else {
                onRequestPermission(PermissionType.SHIZUKU)
            }
        }
        else -> {
            if (isSafDir(path)) {
                if (path.uri(false).isInPersistedUriPermissions()) {
                    granted()
                } else {
                    onRequestPermission(PermissionType.STORAGE_ACCESS_FRAMEWORK)
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        granted()
                    } else {
                        onRequestPermission(PermissionType.MANAGE_STORAGE)
                    }
                } else {
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        granted()
                    } else {
                        onRequestPermission(PermissionType.EXTERNAL_STORAGE)
                    }
                }
            }
        }
    }
}
