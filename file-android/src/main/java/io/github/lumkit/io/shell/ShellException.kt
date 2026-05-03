package io.github.lumkit.io.shell

open class ShellException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ShellTimeoutException(message: String) : ShellException(message)
