// Modified from io.github.lumkit.io (LintFile, LGPL v2.1), original author: lumkit.
// Modified by qbhx224 on 2026-08-05. See README.md and NOTICE.
package io.github.qbhx224.lintfile.io.shell

import rikka.shizuku.Shizuku
import java.io.IOException

object ShellExecutor {
    private var extraEnvPath: String? = ""
    private var defaultEnvPath = ""

    fun setExtraEnvPath(extraEnvPath: String) {
        ShellExecutor.extraEnvPath = extraEnvPath
    }

    private fun getEnvPath(): String? {
        if (!extraEnvPath.isNullOrBlank()) {
            if (defaultEnvPath.isEmpty()) {
                defaultEnvPath = try {
                    val process = Runtime.getRuntime().exec("sh")
                    val outputStream = process.outputStream
                    outputStream.write("echo \$PATH".toByteArray())
                    outputStream.flush()
                    outputStream.close()
                    val inputStream = process.inputStream
                    val cache = ByteArray(16384)
                    val length = inputStream.read(cache)
                    inputStream.close()
                    process.destroy()
                    val path = String(cache, 0, length).trim { it <= ' ' }
                    if (path.isNotEmpty()) {
                        path
                    } else {
                        throw RuntimeException("未能获取到\$PATH参数")
                    }
                } catch (ex: Exception) {
                    "/sbin:/system/sbin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"
                }
            }
            val path = defaultEnvPath
            return "PATH=$path:$extraEnvPath"
        }
        return null
    }

    @Throws(IOException::class)
    fun getRuntime(): Process? {
        val env = getEnvPath()
        val process = Runtime.getRuntime().exec("sh")
        if (env != null) {
            val outputStream = process.outputStream
            outputStream.write("export ".toByteArray())
            outputStream.write(env.toByteArray())
            outputStream.write("\n".toByteArray())
            outputStream.flush()
        }
        return process
    }

    @Throws(IOException::class)
    fun getShizukuProcess(run: String = "sh"): Process? {
        val env = getEnvPath()
        val process = Shizuku.newProcess(arrayOf(run), if (env != null) arrayOf(env) else null, null)
        if (env != null) {
            val outputStream = process.outputStream
            outputStream.write("export ".toByteArray())
            outputStream.write(env.toByteArray())
            outputStream.write("\n".toByteArray())
            outputStream.flush()
        }
        return process
    }
}
