package io.github.lumkit.io

import android.os.Build
import android.os.Environment
import io.github.lumkit.io.data.IoModel
import io.github.lumkit.io.impl.DefaultFile
import io.github.lumkit.io.impl.ShizukuFile
import io.github.lumkit.io.impl.StorageAccessFrameworkFile
import io.github.lumkit.io.shell.AdbShellPublic
import io.github.lumkit.io.shell.ShellException
import io.github.lumkit.io.shell.ShellThreadPool
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
        return disguiseProbeCache.computeIfAbsent(root) {
            val disguisedRoot = File(disguisedOf(root))
            disguisedRoot.isDirectory && disguisedRoot.list() != null
        }
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

/** FIFO 传输命令超时(毫秒):大文件传输可能持续数分钟,不能用默认的 30s */
private const val FIFO_CMD_TIMEOUT_MS = 30 * 60 * 1000L

@Throws(java.io.IOException::class)
fun createTempFIFO(): File {
    val tmpDir = getShizukuTmpDir()
    if (!tmpDir.exists()) tmpDir.mkdirs()
    cleanupStaleFifos()
    val fifo = File(tmpDir, "lintfile-fifo-${UUID.randomUUID()}.tmp")
    val fifoArg = fifo.absolutePath.escapeShellArg()
    // 必须用真正的 FIFO:普通文件会在 cat 写入前被读取端读到 EOF,导致数据丢失。
    // chmod 666 保证应用进程(非 root)能打开读写端。
    val cmd = "(mkfifo $fifoArg && chmod 666 $fifoArg) && echo 1 || echo 0"
    val created = try {
        AdbShellPublic.doCmdSync(cmd) == "1"
    } catch (e: ShellException) {
        false
    }
    if (!created) {
        throw IOException("Cannot create fifo: ${fifo.absolutePath}")
    }
    activeFifos.add(fifo)
    return fifo
}

/** 残留 FIFO 清理阈值:创建超过该时长且无活跃流持有的视为崩溃残留 */
private const val FIFO_STALE_AGE_MILLIS = 5 * 60 * 1000L

/** 当前活跃(正在传输)的 FIFO,防止清理误删 */
private val activeFifos = ConcurrentHashMap.newKeySet<File>()

private fun cleanupStaleFifos() {
    val cutoff = System.currentTimeMillis() - FIFO_STALE_AGE_MILLIS
    getShizukuTmpDir().listFiles()?.forEach { f ->
        if (f.name.startsWith("lintfile-fifo-") && !activeFifos.contains(f) && f.lastModified() < cutoff) {
            f.delete()
        }
    }
}

private fun releaseFifo(fifo: File) {
    activeFifos.remove(fifo)
    fifo.delete()
}

private fun getShizukuTmpDir(): File {
    return if (Build.VERSION.SDK_INT >= 35) {
        val dir = File(LintFileConfiguration.instance.context.cacheDir, "lint-file-tmp")
        dir.mkdirs()
        dir.setReadable(true, false)
        dir.setWritable(true, false)
        dir
    } else {
        File("/data/local/tmp", ".lint-file-tmp")
    }
}

/** FIFO 打开超时时间(毫秒) */
private const val FIFO_OPEN_TIMEOUT = 2000L

private fun ShizukuFile.newInputStream(): InputStream {
    if (isDirectory() || !canRead()) throw FileNotFoundException("No such file or directory: $path")
    val fifo = createTempFIFO()
    try {
        val src = path.stripHiddenChar().escapeShellArg()
        val fifoArg = fifo.absolutePath.escapeShellArg()
        val cmd = "(cat $src > $fifoArg) && echo 1 || echo 0"

        // 1. 先阻塞打开读端,等待写端出现
        val opened = CountDownLatch(1)
        var readEnd: InputStream? = null
        val openTask = ShellThreadPool.submit {
            try {
                readEnd = FileInputStream(fifo)
            } finally {
                opened.countDown()
            }
        }
        if (!opened.await(FIFO_OPEN_TIMEOUT, TimeUnit.MILLISECONDS)) {
            openTask.cancel(true)
            throw FileNotFoundException("Cannot open fifo: $path")
        }

        // 2. 再启动写入端,读写并行,避免管道缓冲区占满导致死等
        val catFuture = ShellThreadPool.submit {
            try {
                AdbShellPublic.doCmdSync(cmd, FIFO_CMD_TIMEOUT_MS)
            } catch (e: ShellException) {
                throw FileNotFoundException("cat: $path: Permission denied").initCause(e)
            }
        }

        val raw = readEnd!!
        return object : InputStream() {
            override fun read(): Int = raw.read()

            override fun read(b: ByteArray?): Int = raw.read(b)

            override fun read(b: ByteArray?, off: Int, len: Int): Int = raw.read(b, off, len)

            override fun available(): Int = raw.available()

            override fun close() {
                try {
                    raw.close()
                    checkCatResult(catFuture, path, isRead = true)
                } finally {
                    releaseFifo(fifo)
                }
            }
        }
    } catch (e: Exception) {
        releaseFifo(fifo)
        if (e is FileNotFoundException) throw e
        val cause = e.cause
        if (cause is FileNotFoundException) throw cause
        val err = FileNotFoundException("Cannot open fifo: $path").initCause(e)
        throw (err as FileNotFoundException)
    }
}

/**
 * 等待后台 cat 命令完成,确保数据完整落盘,并透传失败原因。
 * 写入路径会校验 cat 的结果码,避免磁盘满/权限不足导致的静默数据丢失。
 */
private fun checkCatResult(future: Future<*>, path: String, isRead: Boolean) {
    val action = if (isRead) "read" else "write"
    try {
        val result = future.get(CAT_RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS) as? String
        if (!isRead && result != "1") {
            throw FileNotFoundException("Cannot $action file: $path")
        }
    } catch (e: TimeoutException) {
        throw FileNotFoundException("Timeout while $action file: $path")
    } catch (e: ExecutionException) {
        val cause = e.cause
        if (cause is FileNotFoundException) throw cause
        throw FileNotFoundException("Cannot $action file: $path").initCause(cause)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw FileNotFoundException("Cannot $action file: $path").initCause(e)
    }
}

/** cat 结果等待超时(毫秒):兜底防止 shell 异常时 close() 永久阻塞,须大于传输命令超时 */
private const val CAT_RESULT_TIMEOUT_MS = FIFO_CMD_TIMEOUT_MS + 10_000L

private fun ShizukuFile.newOutputStream(): OutputStream {
    if (isDirectory()) throw FileNotFoundException("$path is not a file but a directory")
    if (!exists() && !createNewFile()) {
        throw FileNotFoundException("Cannot create file $path")
    }

    val fifo = createTempFIFO()
    try {
        val dest = path.stripHiddenChar().escapeShellArg()
        val fifoArg = fifo.absolutePath.escapeShellArg()
        val cmd = "(cat $fifoArg > $dest) && echo 1 || echo 0"

        // 1. 先阻塞打开写端,等待读端出现
        val opened = CountDownLatch(1)
        var writeEnd: OutputStream? = null
        val openTask = ShellThreadPool.submit {
            try {
                writeEnd = FileOutputStream(fifo)
            } finally {
                opened.countDown()
            }
        }
        if (!opened.await(FIFO_OPEN_TIMEOUT, TimeUnit.MILLISECONDS)) {
            openTask.cancel(true)
            throw FileNotFoundException("Cannot open fifo: $path")
        }

        // 2. 再启动消费端,边写边消费
        val catFuture = ShellThreadPool.submit {
            try {
                AdbShellPublic.doCmdSync(cmd, FIFO_CMD_TIMEOUT_MS)
            } catch (e: ShellException) {
                throw FileNotFoundException("Cannot write to file $path").initCause(e)
            }
        }

        val raw = writeEnd!!
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
                    raw.close()
                    checkCatResult(catFuture, path, isRead = false)
                } finally {
                    releaseFifo(fifo)
                }
            }
        }
    } catch (e: Exception) {
        releaseFifo(fifo)
        if (e is FileNotFoundException) throw e
        val cause = e.cause
        if (cause is FileNotFoundException) throw cause
        val err = FileNotFoundException("Cannot open fifo: $path").initCause(e)
        throw (err as FileNotFoundException)
    }
}
