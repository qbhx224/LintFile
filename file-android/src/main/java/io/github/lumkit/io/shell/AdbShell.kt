package io.github.lumkit.io.shell

import android.util.Log
import java.io.BufferedReader
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock

class AdbShell {
    private var process: Process? = null
    private var out: OutputStream? = null
    private var reader: BufferedReader? = null

    @Volatile
    private var currentIsIdle = true
    val isIdle: Boolean
        get() = currentIsIdle

    private val mLock = ReentrantLock()
    private val LOCK_TIMEOUT = 10000L

    fun tryExit() {
        try {
            out?.close()
            reader?.close()
        } catch (e: Exception) {
            Log.w("AdbShell", "Error closing streams: ${e.message}")
        }
        try {
            process?.destroy()
        } catch (e: Exception) {
            Log.w("AdbShell", "Error destroying process: ${e.message}")
        }
        out = null
        reader = null
        process = null
        currentIsIdle = true
    }

    private fun getRuntimeShell() {
        if (process != null) return
        try {
            mLock.lockInterruptibly()
            if (process != null) return // double-check after lock acquired
            process = ShellExecutor.getShizukuProcess()
            out = process?.outputStream
            reader = process?.inputStream?.bufferedReader()
            startErrorStreamDrain()
        } catch (ex: Exception) {
            Log.e("AdbShell", "Failed to start shell: ${ex.message}")
            tryExit()
        } finally {
            if (mLock.isHeldByCurrentThread) mLock.unlock()
        }
    }

    private fun startErrorStreamDrain() {
        val errorReader = process?.errorStream?.bufferedReader() ?: return
        Thread({
            try {
                while (true) {
                    val line = errorReader.readLine() ?: break
                    Log.d("AdbShell", "stderr: $line")
                }
            } catch (ex: Exception) {
                Log.d("AdbShell", "Error stream drain ended: ${ex.message}")
            }
        }, "adb-shell-stderr").apply { isDaemon = true }.start()
    }

    private val shellOutputCache = StringBuilder()
    private val endTag = "|<<SH|"
    private val endTagBytes = "echo '$endTag'\n".toByteArray(Charset.defaultCharset())

    fun doCmdSync(cmd: String, timeoutMs: Long = 30000): String {
        getRuntimeShell()
        // 等待锁有上限,但绝不中断正在执行的命令:kill 共享 shell 会导致
        // 正在进行的 FIFO 传输损坏。每条命令本身有 timeoutMs 超时兜底。
        val locked = try {
            mLock.tryLock(LOCK_TIMEOUT, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ShellException("Interrupted while waiting for shell lock", e)
        }
        if (!locked) {
            throw ShellException("Shell is busy, another command is still running")
        }
        try {
            currentIsIdle = false
            out?.run {
                write("$cmd\n".toByteArray(Charset.defaultCharset()))
                write(endTagBytes)
                flush()
            }
            reader?.also {
                shellOutputCache.clear()
                val deadline = System.currentTimeMillis() + timeoutMs
                while (true) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) {
                        tryExit()
                        throw ShellTimeoutException("Shell command timed out after ${timeoutMs}ms: $cmd")
                    }
                    val lineFuture = java.util.concurrent.FutureTask<String> { it.readLine() }
                    Thread(lineFuture, "adb-shell-read").start()
                    val line = try {
                        lineFuture.get(remaining, TimeUnit.MILLISECONDS)
                    } catch (e: TimeoutException) {
                        lineFuture.cancel(true)
                        tryExit()
                        throw ShellTimeoutException("Shell command timed out after ${timeoutMs}ms: $cmd")
                    } catch (e: Exception) {
                        tryExit()
                        throw ShellException("Shell read error: ${e.message}", e)
                    }
                    if (line == null) {
                        tryExit()
                        throw ShellException("Shell process terminated unexpectedly")
                    }
                    if (line.contains(endTag)) break
                    shellOutputCache.append(line).append("\n")
                }
            }
            return shellOutputCache.let {
                if (it.isEmpty()) {
                    it
                } else {
                    it.substring(0, it.length - 1)
                }
            }.toString()
        } catch (e: ShellException) {
            throw e
        } catch (e: Exception) {
            tryExit()
            throw ShellException("Shell command failed: ${e.message}", e)
        } finally {
            if (mLock.isHeldByCurrentThread) mLock.unlock()
            currentIsIdle = true
        }
    }
}
