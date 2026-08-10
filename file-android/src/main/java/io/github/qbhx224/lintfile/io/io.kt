// Modified from io.github.lumkit.io (LintFile, LGPL v2.1), original author: lumkit.
// Modified by qbhx224 on 2026-08-05. See README.md and NOTICE.
package io.github.qbhx224.lintfile.io

import android.os.Build
import android.os.Environment
import io.github.qbhx224.lintfile.io.data.IoModel
import io.github.qbhx224.lintfile.io.impl.DefaultFile
import io.github.qbhx224.lintfile.io.impl.ShizukuFile
import io.github.qbhx224.lintfile.io.impl.StorageAccessFrameworkFile
import io.github.qbhx224.lintfile.io.shell.ShellException
import io.github.qbhx224.lintfile.io.shell.ShellExecutor
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import rikka.shizuku.ShizukuRemoteProcess

internal const val ZERO_WIDTH_JOINER = "\u200D"

/**
 * 剥离路径中用于绕过 /Android/data 拦截的零宽连接符,得到真实路径
 */
internal fun String.stripHiddenChar(): String = replace(ZERO_WIDTH_JOINER, "")

/**
 * POSIX shell 单引号参数转义,杜绝命令注入
 */
internal fun String.escapeShellArg(): String = "'" + replace("'", "'\\''") + "'"

/**
 * 路径规范化:
 * 1. 先剥离所有零宽连接符,保证幂等(避免重复叠加导致路径不可读)
 * 2. Android 11+ 且路径位于 /Android 子树下时,探测带零宽连接符的路径是否可读,
 *    可读则采用该形态绕过系统对 /Android/data 的拦截
 */
internal fun String.pathNormalize(): String {
    val pure = stripHiddenChar()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return pure
    if (!pure.isUnderAndroidDir()) return pure
    return if (canUseDisguisedSubtree(pure)) disguisedOf(pure) else pure
}

/** /Android 路径应用零宽连接符伪装后的形态 */
internal fun disguisedOf(pure: String): String =
    pure.replace("Android", "Android$ZERO_WIDTH_JOINER")

/**
 * 定位路径所属的"锁定子树"根目录:
 * `/Android/data/<包名>` 或 `/Android/obb`。非锁定区域返回 null。
 */
internal fun String.lockedSubtreeRoot(): String? {
    val androidIdx = indexOf("/Android")
    if (androidIdx < 0) return null
    val base = substring(0, androidIdx + "/Android".length)
    for (suffix in listOf("/data", "/obb")) {
        val subtree = base + suffix
        if (!startsWith("$subtree/")) continue
        // obb 整层锁定,data 层按包名分组
        if (suffix == "/obb") return subtree
        val rest = substring(subtree.length + 1)
        if (rest.isEmpty()) return subtree
        val first = rest.indexOf('/')
        return if (first < 0) "$subtree/$rest" else "$subtree/${rest.substring(0, first)}"
    }
    return null
}

private val disguiseProbeCache = ConcurrentHashMap<String, Boolean>()
private val safAccessCache = ConcurrentHashMap<String, Boolean>()

/**
 * 判定 /Android 子树是否可通过零宽连接符伪装路径绕过拦截。
 * 以伪装后的锁定子树根目录能否成功列出为准(比 canRead 探测更可靠,
 * 部分设备 canRead 返回 true 但实际 list/open 仍被拦截),
 * 结果按子树缓存;非锁定区域回退到对目标文件本身的 canRead 探测。
 */
private fun canUseDisguisedSubtree(pure: String): Boolean {
    val root = pure.lockedSubtreeRoot()
    if (root != null) {
        // 目录不存在时不缓存:应用首次访问后可能才创建目录,
        // 缓存 false 会导致目录创建后仍判定"不可绕过",需允许后续重新探测
        disguiseProbeCache[root]?.let { return it }
        val disguisedRoot = File(disguisedOf(root))
        if (!disguisedRoot.exists()) return false
        val usable = disguisedRoot.isDirectory && disguisedRoot.list() != null
        disguiseProbeCache[root] = usable
        return usable
    }
    return File(disguisedOf(pure)).canRead()
}

/**
 * 创建一个通用的File
 */
fun file(path: String): LintFile =
    when (LintFileConfiguration.instance.ioMode) {
        IoModel.SHIZUKU -> ShizukuFile(path)
        else -> createUserFile(path)
    }

/**
 * 创建一个通用的File
 */
fun file(file: LintFile): LintFile =
    when (LintFileConfiguration.instance.ioMode) {
        IoModel.SHIZUKU -> ShizukuFile(file)
        else -> createUserFile(file.path)
    }

/**
 * 创建一个通用的File
 */
fun file(dir: LintFile, child: String): LintFile =
    when (LintFileConfiguration.instance.ioMode) {
        IoModel.SHIZUKU -> ShizukuFile(dir, child)
        else -> createUserFile(File(dir.path, child).absolutePath)
    }

/**
 * 创建用户权限级别的File
 */
private fun createUserFile(path: String): LintFile =
    if (isSafDir(path)) {
        StorageAccessFrameworkFile(path)
    } else {
        DefaultFile(path)
    }

/**
 * 判断路径是否位于外部存储的 /Android 目录下
 */
internal fun String.isUnderAndroidDir(): Boolean {
    val pure = stripHiddenChar()
    val android = File(Environment.getExternalStorageDirectory(), "Android").absolutePath
    return pure.startsWith("$android/") || pure == android
}

/**
 * 判断该路径是否需要通过 SAF 框架访问
 */
fun isSafDir(path: String): Boolean {
    val pure = path.stripHiddenChar()
    if (!pure.isUnderAndroidDir()) return false
    if (LintFileConfiguration.instance.useSaf) return true
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    // 零宽连接符绕过可用时,普通文件系统即可访问,无需 SAF
    if (canUseDisguisedSubtree(pure)) return false
    val root = pure.lockedSubtreeRoot()
    return if (root != null) {
        // FUSE 对锁定子树的拦截策略统一,按子树根缓存探测结果,避免每个文件两次 syscall
        !safAccessCache.computeIfAbsent(root) { File(it).canRead() && File(it).canWrite() }
    } else {
        val f = File(pure)
        !(f.canRead() && f.canWrite())
    }
}

/**
 * 路径处理兼容函数:
 * 内部对输入路径做规范化处理,对外语义保持与旧版本一致
 */
fun String.pathHandle(hide: Boolean = true): String =
    if (hide) pathNormalize() else stripHiddenChar()

/**
 * 解析 stat 输出的秒级时间戳为毫秒(与 java.io.File.lastModified 语义一致)。
 * 解析失败返回 0。
 */
internal fun String.parseStatSecondsToMillis(): Long =
    trim().toLongOrNull()?.let { it * 1000 } ?: 0

/**
 * 解析 stat 输出的字节数。解析失败返回 0。
 */
internal fun String.parseStatSize(): Long =
    trim().toLongOrNull() ?: 0

/**
 * 解析 ls -a 输出,过滤 "."、".." 与空行。
 */
internal fun String.parseLsOutput(): Array<String> =
    split("\n").filter { it.isNotEmpty() && it != "." && it != ".." }.toTypedArray()

/**
 * 解析 `ls -la` 输出为文件条目数组。
 *
 * 基于 Android toybox 实测格式:`<权限> <链接数> <属主> <属组> <大小> <日期> <时间> <名称>`,
 * 字段间可能有多空格;toybox 对旧文件也输出完整 `YYYY-MM-DD HH:MM`(无 GNU 的年份回退)。
 * 防御性兼容:时间列为纯年份时按日期解析(00:00)。
 */
internal fun String.parseLsLaOutput(): Array<LintFileInfo> {
    val result = ArrayList<LintFileInfo>()
    for (line in lines()) {
        line.parseLsLaLine()?.let { result.add(it) }
    }
    return result.toTypedArray()
}

internal fun String.parseLsLaLine(): LintFileInfo? {
    val parts = trim().split(Regex("\\s+"))
    if (parts.size < 8) return null
    val type = parts[0].firstOrNull()
    if (type == null || (type != 'd' && type != '-' && type != 'l')) return null
    val size = parts[4].toLongOrNull() ?: return null
    var name = parts.drop(7).joinToString(" ").trim()
    if (name.isEmpty() || name == "." || name == "..") return null
    if (type == 'l') {
        name = name.substringBefore(" -> ").trim()
    }
    return LintFileInfo(
        name = name,
        size = size,
        lastModified = parseLsDate(parts[5], parts.getOrNull(6).orEmpty()),
        isDirectory = type == 'd',
        isFile = type == '-'
    )
}

private fun parseLsDate(date: String, timeOrYear: String): Long {
    if (timeOrYear.length == 4 && timeOrYear.all { it.isDigit() }) {
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(date)?.time ?: 0L
        } catch (e: ParseException) {
            0L
        }
    }
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).parse("$date $timeOrYear")?.time ?: 0L
    } catch (e: ParseException) {
        0L
    }
}


@Throws(IOException::class)
fun LintFile.openInputStream(): InputStream =
    when (this) {
        is StorageAccessFrameworkFile -> LintFileConfiguration.instance
            .context
            .contentResolver
            .openInputStream(path.documentReallyUri())
            ?: throw IOException("No such file or directory")

        is ShizukuFile -> newInputStream()

        else -> FileInputStream(path)
    }

@Throws(IOException::class)
fun LintFile.openOutputStream(): OutputStream =
    when (this) {
        is StorageAccessFrameworkFile -> LintFileConfiguration.instance
            .context
            .contentResolver
            .openOutputStream(path.documentReallyUri(), "rwt")
            ?: throw IOException("No such file or directory")

        is ShizukuFile -> newOutputStream()
        else -> FileOutputStream(path)
    }

/**
 * Shizuku 文件读写通过一次性进程管道完成:
 *
 * 读: `cat <path>` 进程的 stdout 经 ParcelFileDescriptor 直达应用,不落盘、不共享文件
 * 写: `sh -c "exec cat > '<path>'"` 进程,数据写入其 stdin,关闭后触发 EOF 落盘
 *
 * 彻底规避 FIFO 方案的跨 SELinux 域共享文件问题(shell 域进不了 app 数据目录,
 * app 域也进不了 shell_data_file),非 root 的 Shizuku(shell 权限)即可工作。
 */
private const val PROCESS_WAIT_TIMEOUT_MS = 5 * 60 * 1000L

private fun ShizukuFile.newInputStream(): InputStream {
    if (isDirectory() || !canRead()) throw FileNotFoundException("No such file or directory: $path")
    // path 原样传入 argv(保留零宽连接符伪装路径,shell 下同样可绕过 FUSE 拦截)
    val process = try {
        ShellExecutor.newProcess(arrayOf("cat", path))
    } catch (e: IOException) {
        throw FileNotFoundException("Cannot open file: $path").initCause(e)
    }
    drainStderr(process)
    val raw = process.inputStream
    return object : InputStream() {
        override fun read(): Int = raw.read()

        override fun read(b: ByteArray?): Int = raw.read(b)

        override fun read(b: ByteArray?, off: Int, len: Int): Int = raw.read(b, off, len)

        override fun available(): Int = raw.available()

        override fun close() {
            try {
                raw.close()
                finishProcess(process, path, isRead = true)
            } finally {
                process.destroy()
            }
        }
    }
}

private fun ShizukuFile.newOutputStream(): OutputStream {
    if (isDirectory()) throw FileNotFoundException("$path is not a file but a directory")
    if (!exists() && !createNewFile()) {
        throw FileNotFoundException("Cannot create file $path")
    }

    // exec 让 cat 取代 sh,信号与退出码语义更清晰
    val cmd = "exec cat > " + path.escapeShellArg()
    val process = try {
        ShellExecutor.newProcess(arrayOf("sh", "-c", cmd))
    } catch (e: IOException) {
        throw FileNotFoundException("Cannot open file for writing: $path").initCause(e)
    }
    drainStderr(process)
    val raw = process.outputStream
    return object : OutputStream() {
        override fun write(b: Int) {
            raw.write(b)
        }

        override fun write(b: ByteArray) {
            raw.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            raw.write(b, off, len)
        }

        override fun flush() {
            raw.flush()
        }

        override fun close() {
            try {
                // 关闭 stdin 触发 EOF,cat 结束并落盘
                raw.close()
                finishProcess(process, path, isRead = false)
            } finally {
                process.destroy()
            }
        }
    }
}

/**
 * 等待远程进程结束并校验退出码。
 * 读路径:cat 失败(权限不足等)时 stdout 为空,close 时抛出明确错误
 * 写路径:磁盘满/权限不足等导致 cat 非零退出时抛出,避免静默数据丢失
 */
private fun finishProcess(process: Process, path: String, isRead: Boolean) {
    val action = if (isRead) "read" else "write"
    try {
        val finished = if (process is ShizukuRemoteProcess) {
            process.waitForTimeout(PROCESS_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } else {
            process.waitFor(PROCESS_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        if (!finished) {
            process.destroy()
            throw FileNotFoundException("Timeout while $action file: $path")
        }
        if (process.exitValue() != 0) {
            throw FileNotFoundException("Cannot $action file: $path (exit ${process.exitValue()})")
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        process.destroy()
        throw FileNotFoundException("Cannot $action file: $path").initCause(e)
    }
}

/** 后台排空 stderr,防止错误输出积压堵塞管道 */
private fun drainStderr(process: Process) {
    Thread({
        try {
            process.errorStream?.use { it.readBytes() }
        } catch (e: Exception) {
            // 流已关闭等正常情况,忽略
        }
    }, "lintfile-process-stderr").apply { isDaemon = true }.start()
}
