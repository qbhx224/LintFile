package io.github.lumkit.io.shell

object AdbShellPublic {

    private val pool = HashMap<String, AdbShell>()

    fun getInstance(key: String): AdbShell {
        synchronized(pool) {
            if (!pool.containsKey(key)) {
                pool[key] = AdbShell()
            }
            return pool[key]!!
        }
    }

    fun destroyInstance(key: String) {
        synchronized(pool) {
            if (!pool.containsKey(key)) {
                return
            } else {
                val keepShell = pool[key]!!
                pool.remove(key)
                keepShell.tryExit()
            }
        }
    }

    fun destroyAll() {
        synchronized(pool) {
            while (pool.isNotEmpty()) {
                val key = pool.keys.first()
                val keepShell = pool[key]!!
                pool.remove(key)
                keepShell.tryExit()
            }
        }
    }

    val defaultKeepShell by lazy { AdbShell() }
    val secondaryKeepShell by lazy { AdbShell() }

    val shell by lazy { getInstance("shell-default") }

    fun getDefaultInstance(): AdbShell {
        return if (defaultKeepShell.isIdle) {
            defaultKeepShell
        } else if (secondaryKeepShell.isIdle) {
            secondaryKeepShell
        } else {
            defaultKeepShell
        }
    }

    fun doCmdSync(commands: List<String>): Boolean {
        val stringBuilder = StringBuilder()
        for (cmd in commands) {
            stringBuilder.append(cmd)
            stringBuilder.append("\n\n")
        }
        return try {
            doCmdSync(stringBuilder.toString())
            true
        } catch (e: ShellException) {
            false
        }
    }

    fun doCmdSync(cmd: String): String {
        return getDefaultInstance().doCmdSync(cmd)
    }

    fun doCmdSync(cmd: String, timeoutMs: Long): String {
        return getDefaultInstance().doCmdSync(cmd, timeoutMs)
    }

    fun tryExit() {
        defaultKeepShell.tryExit()
        secondaryKeepShell.tryExit()
    }

}
