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
        val process = startShizukuProcess(run, env)
        if (env != null) {
            val outputStream = process.outputStream
            outputStream.write("export ".toByteArray())
            outputStream.write(env.toByteArray())
            outputStream.write("\n".toByteArray())
            outputStream.flush()
        }
        return process
    }

    /**
     * 通过反射调用 `Shizuku.newProcess`。
     *
     * Shizuku 13.1.0 中该方法为 public,13.1.5 起改为 private(并计划在 API 14 移除),
     * 直接调用会在高版本 Shizuku 下抛出 IllegalAccessError 导致崩溃。
     * 反射兼容两种版本;若方法在未来的 Shizuku API 中完全移除,会抛出带明确信息的 IOException。
     */
    @Throws(IOException::class)
    private fun startShizukuProcess(run: String, env: String?): Process {
        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            return method.invoke(
                null,
                arrayOf(run),
                if (env != null) arrayOf(env) else null,
                null
            ) as Process
        } catch (e: Exception) {
            throw IOException(
                "Cannot start Shizuku process: Shizuku.newProcess is unavailable in the current Shizuku API version",
                e
            )
        }
    }
}
