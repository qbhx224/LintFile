package io.github.lumkit.io.impl

import io.github.lumkit.io.LintFile
import io.github.lumkit.io.shell.AdbShellPublic
import io.github.lumkit.io.shell.ShellException
import io.github.lumkit.io.stripHiddenChar

class ShizukuFile : LintFile {

    constructor(path: String) : super(path)
    constructor(file: LintFile) : super(file)
    constructor(file: LintFile, child: String) : super(file, child)

    private fun safePath(): String = path.stripHiddenChar().replace("\"", "\\\"").replace("$", "\$")

    override fun exists(): Boolean =
        try {
            AdbShellPublic.doCmdSync("[ -e \"${safePath()}\" ] && echo 1 || echo 0") == "1"
        } catch (e: ShellException) {
            false
        }

    override fun getParent(): String = _file.parent?.stripHiddenChar() ?: ""

    override fun getParentFile(): LintFile = ShizukuFile(getParent())

    override fun canRead(): Boolean =
        try {
            AdbShellPublic.doCmdSync("[ -r \"${safePath()}\" ] && echo 1 || echo 0") == "1"
        } catch (e: ShellException) {
            false
        }

    override fun canWrite(): Boolean =
        try {
            AdbShellPublic.doCmdSync("[ -w \"${safePath()}\" ] && echo 1 || echo 0") == "1"
        } catch (e: ShellException) {
            false
        }

    override fun isDirectory(): Boolean =
        try {
            AdbShellPublic.doCmdSync("[ -d \"${safePath()}\" ] && echo 1 || echo 0") == "1"
        } catch (e: ShellException) {
            false
        }

    override fun isFile(): Boolean =
        try {
            AdbShellPublic.doCmdSync("[ -f \"${safePath()}\" ] && echo 1 || echo 0") == "1"
        } catch (e: ShellException) {
            false
        }

    override fun lastModified(): Long = try {
        AdbShellPublic.doCmdSync("stat -c '%Y' \"${safePath()}\"").toLong()
    } catch (e: Exception) {
        0
    }

    override fun length(): Long = try {
        AdbShellPublic.doCmdSync("stat -c '%s' \"${safePath()}\"").toLong()
    } catch (e: Exception) {
        0
    }

    override fun createNewFile(): Boolean =
        try {
            AdbShellPublic.doCmdSync("[ ! -e \"${safePath()}\" ] && echo -n > \"${safePath()}\" && echo 1 || echo 0") == "1"
        } catch (e: ShellException) {
            false
        }

    override fun delete(): Boolean =
        try {
            AdbShellPublic.doCmdSync("(rm -rf \"${safePath()}\") && echo 1 || echo 0") == "1"
        } catch (e: ShellException) {
            false
        }

    override fun list(): Array<String> {
        if (!isDirectory())
            return arrayOf()
        val cmd = "ls -a \"${safePath()}\""

        val list = try {
            ArrayList(AdbShellPublic.doCmdSync(cmd).split("\n"))
        } catch (e: ShellException) {
            return arrayOf()
        }
        val iterator = list.listIterator()

        while (iterator.hasNext()) {
            val name: String = iterator.next()
            if (name == "." || name == "..") {
                iterator.remove()
            }
        }

        return list.map { "${path.stripHiddenChar()}/$it" }.toTypedArray()
    }

    override fun list(filter: (String) -> Boolean): Array<String> = list().filter { filter(it) }.toTypedArray()

    override fun listFiles(): Array<LintFile> = list().map { ShizukuFile(it) }.toTypedArray()

    override fun listFiles(filter: (LintFile) -> Boolean): Array<LintFile> = listFiles().filter { filter(it) }.toTypedArray()

    override fun mkdirs(): Boolean =
        try {
            AdbShellPublic.doCmdSync("mkdir -p \"${safePath()}\" && echo 1 || echo 0") == "1"
        } catch (e: ShellException) {
            false
        }

    override fun renameTo(dest: String): Boolean {
        val cmd = "mv -f \"${safePath()}\" \"${getParent()}/${dest.stripHiddenChar().replace("\"", "\\\"").replace("$", "\$")}\" && echo 1 || echo 0"
        return try {
            AdbShellPublic.doCmdSync(cmd) == "1"
        } catch (e: ShellException) {
            false
        }
    }

    fun clear(): Boolean {
        val cmd = "(echo -n > \"${safePath()}\") && echo 1 || echo 0"
        return try {
            AdbShellPublic.doCmdSync(cmd) == "1"
        } catch (e: ShellException) {
            false
        }
    }
}
