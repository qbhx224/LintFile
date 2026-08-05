// Modified from io.github.lumkit.io (LintFile, LGPL v2.1), original author: lumkit.
// Modified by qbhx224 on 2026-08-05. See README.md and NOTICE.
package io.github.qbhx224.lintfile.io

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.app.ActivityCompat
import io.github.qbhx224.lintfile.io.data.IoModel
import io.github.qbhx224.lintfile.io.data.PermissionType
import io.github.qbhx224.lintfile.io.shell.ShizukuUtil
import rikka.shizuku.Shizuku

/**
 * 判断路径是否位于应用专属目录(无需任何存储权限即可读写)
 */
internal fun LintFile.isAppSpecificDir(): Boolean {
    val context = LintFileConfiguration.instance.context
    val pure = path.stripHiddenChar()
    return listOf(
        context.filesDir.absolutePath,
        context.cacheDir.absolutePath,
        context.getExternalFilesDir(null)?.absolutePath,
        context.externalCacheDir?.absolutePath,
    ).filterNotNull().any { pure.startsWith(it) }
}

/**
 * 文件权限安全作用域
 *
 * @param onRequestPermission 需要申请权限时的回调,可通过 [PermissionType] 区分权限类型。
 *                            申请完成后需重新调用本函数以重试。
 * @param granted 权限已满足时的回调,作用域内可通过 this: LintFile 调用文件操作 API
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
                when {
                    // 应用专属目录无需任何存储权限
                    isAppSpecificDir() -> granted()

                    // Android 11+ 访问共享存储需要"所有文件访问权限"
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                        if (Environment.isExternalStorageManager()) {
                            granted()
                        } else {
                            onRequestPermission(PermissionType.MANAGE_STORAGE)
                        }
                    }

                    // Android 10 及以下需要读写外部存储运行时权限
                    else -> {
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
}
