package io.github.lumkit.io.impl

import io.github.lumkit.io.LintFile
import io.github.lumkit.io.LintFileInfo
import io.github.lumkit.io.escapeShellArg
import io.github.lumkit.io.parseLsLaOutput
import io.github.lumkit.io.parseLsOutput
import io.github.lumkit.io.parseStatSecondsToMillis
import io.github.lumkit.io.parseStatSize
import io.github.lumkit.io.shell.AdbShellPublic
import io.github.lumkit.io.shell.ShellException
import io.github.lumkit.io.stripHiddenChar
import java.io.File

/**
 * 构建与 java.io.File.delete() 语义一致的删除命令:
 * 仅删除文件或空目录;目录非空或路径不存在时返回失败。
 * 严禁使用 rm -rf(会递归清空整个目录树,造成数据丢失)。
 */
internal fun buildDeleteCommand(path: String): String {
    val arg = path.escapeShellArg()
    return "[ -e $arg ] && (rm -f $arg || rmdir $arg) && echo 1 || echo 0"
}

/**
 * 构建递归删除命令:删除文件或整个目录树。
 * 与 [buildDeleteCommand] 不同,这是显式的递归删除 API,请谨慎调用。
 */
internal fun buildDeleteRecursivelyCommand(path: String): String {
    val arg = path.escapeShellArg()
    return "[ -e $arg ] && rm -rf $arg && echo 1 || echo 0"
}

class ShizukuFile : LintFile {

    constructor(path: String) : super(path)
    constructor(file: LintFile) : super(file)
    constructor(file: LintFile, child: String) : super(file, child)

    private fun safeArg(value: String): String = value.stripHiddenChar().escapeShellArg()

    override fun exists(): Boolean =
        try {
            AdbShellPublic.doCmdSync("[ -e ${safeArg(path)} ] && echo 1 || echo 0") == "1"
        } catch (e: ShellException) {
            false
        }

    override fun getParent(): String = _file.parent?.stripHiddenChar() ?: ""

    override fun getParentFile(): LintFile = ShizukuFile(getParent())

    override fun canRead(): Boolean =
        try {
            AdbShellPublic.doCmdSync("[ -r ${safeArg(path)} ] && echo 1 || echo 0") == "1"
        } catch (e: ShellException) {
            false
        }

    override fun canWrite(): Boolean =
        try {
            AdbShellPublic.doCmdSync("[ -w ${safeArg(path)} ] && echo 1 || echo 0") == "1"
        } catch (e: ShellException) {
            false
        }

    override fun isDirectory(): Boolean =
        try {
            AdbShellPublic.doCmdSync("[ -d ${safeArg(path)} ] && echo 1 || echo 0") == "1"
        } catch (e: ShellException) {
            false
        }

    override fun isFile(): Boolean =
        try {
            AdbShellPublic.doCmdSync("[ -f ${safeArg(path)} ] && echo 1 || echo 0") == "1"
        } catch (e: ShellException) {
            false
        }

    override fun lastModified(): Long = try {
        AdbShellPublic.doCmdSync("stat -c '%Y' ${safeArg(path)}").parseStatSecondsToMillis()
    } catch (e: Exception) {
        0
    }

    override fun length(): Long = try {
        AdbShellPublic.doCmdSync("stat -c '%s' ${safeArg(path)}").parseStatSize()
    } catch (e: Exception) {
        0
    }

    override fun createNewFile(): Boolean =
        try {
            AdbShellPublic.doCmdSync("[ ! -e ${safeArg(path)} ] && echo -n > ${safeArg(path)} && echo 1 || echo 0") == "1"
        } catch (e: ShellException) {
            false
        }

    override fun delete(): Boolean =
        try {
            AdbShellPublic.doCmdSync(buildDeleteCommand(path.stripHiddenChar())) == "1"
        } catch (e: ShellException) {
            false
        }

    override fun deleteRecursively(): Boolean =
        try {
            AdbShellPublic.doCmdSync(buildDeleteRecursivelyCommand(path.stripHiddenChar())) == "1"
        } catch (e: ShellException) {
            false
        }

    override fun list(): Array<String> {
        if (!isDirectory())
            return arrayOf()
        val cmd = "ls -a ${safeArg(path)}"

        return try {
            AdbShellPublic.doCmdSync(cmd).parseLsOutput()
        } catch (e: ShellException) {
            arrayOf()
        }
    }

    override fun list(filter: (String) -> Boolean): Array<String> = list().filter { filter(it) }.toTypedArray()

    override fun listFiles(): Array<LintFile> = list().map { ShizukuFile(File(path.stripHiddenChar(), it).absolutePath) }.toTypedArray()

    override fun listFiles(filter: (LintFile) -> Boolean): Array<LintFile> = listFiles().filter { filter(it) }.toTypedArray()

    override fun listFilesWithAttributes(): Array<LintFileInfo> {
        if (!isDirectory())
            return arrayOf()
        // 一次 shell 调用返回整目录条目,避免逐文件 stat 的串行往返
        return try {
            AdbShellPublic.doCmdSync("ls -la ${safeArg(path)}").parseLsLaOutput()
        } catch (e: ShellException) {
            arrayOf()
        }
    }

    override fun mkdirs(): Boolean =
        try {
            AdbShellPublic.doCmdSync("mkdir -p ${safeArg(path)} && echo 1 || echo 0") == "1"
        } catch (e: ShellException) {
            false
        }

    override fun renameTo(dest: String): Boolean {
        val cmd = "mv -f ${safeArg(path)} ${safeArg(dest)} && echo 1 || echo 0"
        return try {
            AdbShellPublic.doCmdSync(cmd) == "1"
        } catch (e: ShellException) {
            false
        }
    }

    fun clear(): Boolean {
        val cmd = "(echo -n > ${safeArg(path)}) && echo 1 || echo 0"
        return try {
            AdbShellPublic.doCmdSync(cmd) == "1"
        } catch (e: ShellException) {
            false
        }
    }
}
