package io.github.lumkit.io.shell

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal object ShellThreadPool {
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "lintfile-shell-io").apply { isDaemon = true }
    }

    fun <T> submit(task: () -> T): Future<T> = executor.submit(task)

    fun shutdown() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
