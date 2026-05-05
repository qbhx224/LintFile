package io.github.lumkit.io

import android.os.Build
import android.os.Environment
import android.system.ErrnoException
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
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal fun String.stripHiddenChar(): String = replace("‍", "")

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
    if (isSafDir(path.pathHandle(true))) {
        StorageAccessFrameworkFile(path)
    } else {
        DefaultFile(path)
    }

fun isSafDir(path: String): Boolean {
    if (LintFileConfiguration.instance.useSaf &&
        path.pathHandle(false).startsWith(
            (File(Environment.getExternalStorageDirectory(), "Android").absolutePath + "/").pathHandle(false)
        )) {
        return true
    }
    val canRead = File(path).canRead()
    val canWrite = File(path).canWrite()
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && path.pathHandle(false).startsWith(
        (File(
            Environment.getExternalStorageDirectory(),
            "Android"
        ).absolutePath + "/").pathHandle(false)
    ) && (!canRead || !canWrite)
}

fun String.pathHandle(hide: Boolean = true): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hide && File(this.replace("Android", "Android‍")).canRead()) {
        this.replace("Android", "Android‍")
    } else {
        this.replace("‍", "")
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

@Throws(
    ErrnoException::class,
    java.io.IOException::class
)
fun createTempFIFO(): File {
    val tmpDir = getShizukuTmpDir()
    if (!tmpDir.exists()) tmpDir.mkdirs()
    val fifo = File(tmpDir, "lintfile-fifo-${UUID.randomUUID()}.tmp")
    fifo.createNewFile()
    return fifo
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

private fun ShizukuFile.newInputStream(): InputStream {
    if (isDirectory() || !canRead()) throw FileNotFoundException("No such file or directory: $path")
    try {
        val fifo = createTempFIFO()
        val cmd = "(cat \"${path.stripHiddenChar()}\" > \"${fifo.absolutePath}\") && echo 1 || echo 0"
        try {
            AdbShellPublic.doCmdSync(cmd)
        } catch (e: ShellException) {
            fifo.delete()
            throw FileNotFoundException("cat: $path: Permission denied")
        }
        val stream = FutureTask<InputStream> { FileInputStream(fifo) }
        ShellThreadPool.submit { stream.run() }
        val inputStream = stream[FIFO_TIMEOUT.toLong(), TimeUnit.MILLISECONDS]
        return object : InputStream() {
            override fun read(): Int = inputStream.read()
            override fun read(b: ByteArray?): Int = inputStream.read(b)
            override fun read(b: ByteArray?, off: Int, len: Int): Int = inputStream.read(b, off, len)
            override fun available(): Int = inputStream.available()
            override fun close() {
                inputStream.close()
                fifo.delete()
            }
        }
    } catch (e: Exception) {
        if (e is FileNotFoundException) throw e
        val cause = e.cause
        if (cause is FileNotFoundException) throw cause
        val err = FileNotFoundException("Cannot open fifo").initCause(e)
        throw (err as FileNotFoundException)
    }
}

private const val FIFO_TIMEOUT = 2000

private fun ShizukuFile.newOutputStream(): OutputStream {
    if (isDirectory()) throw FileNotFoundException("$path is not a file but a directory")

    if (!canWrite() && !createNewFile()) {
        throw FileNotFoundException("Cannot write to file $path")
    } else if (!clear()) {
        throw FileNotFoundException("Failed to clear file $path")
    }

    try {
        val fifo = createTempFIFO()
        val cmd = "(cat \"${fifo.absolutePath.stripHiddenChar()}\" > \"${path.stripHiddenChar()}\") && echo 1 || echo 0"
        try {
            AdbShellPublic.doCmdSync(cmd)
        } catch (e: ShellException) {
            fifo.delete()
            throw FileNotFoundException("Cannot write to file $path")
        }

        val stream = FutureTask<OutputStream> { FileOutputStream(fifo) }
        ShellThreadPool.submit { stream.run() }
        val outputStream = stream[FIFO_TIMEOUT.toLong(), TimeUnit.MILLISECONDS]
        return object : OutputStream() {
            override fun write(b: Int) {
                outputStream.write(b)
            }

            override fun write(b: ByteArray) {
                outputStream.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                outputStream.write(b, off, len)
            }

            override fun flush() {
                outputStream.flush()
            }

            override fun close() {
                if (!fifo.exists())
                    throw FileNotFoundException("No such file or directory: $path")
                try {
                    outputStream.close()
                } finally {
                    try {
                        AdbShellPublic.doCmdSync("(mv -f \"${fifo.path}\" \"${path.stripHiddenChar()}\") && echo 1 || echo 0")
                    } catch (e: ShellException) {
                        fifo.delete()
                        throw FileNotFoundException("Cannot write to file $path")
                    }
                }
            }
        }
    } catch (e: java.lang.Exception) {
        if (e is FileNotFoundException) throw e
        val cause = e.cause
        if (cause is FileNotFoundException) throw cause
        val err = FileNotFoundException("Cannot open fifo").initCause(e)
        throw (err as FileNotFoundException)
    }
}
