// Modified from io.github.lumkit.io (LintFile, LGPL v2.1), original author: lumkit.
// Modified by qbhx224 on 2026-08-05. See README.md and NOTICE.
package io.github.qbhx224.lintfile.io

import java.io.File
import java.io.IOException

abstract class LintFile : Comparator<LintFile> {

    internal val _file: File

    constructor(path: String) {
        this._file = File(path.pathNormalize())
    }
    constructor(file: LintFile) {
        this._file = file._file
    }
    constructor(file: LintFile, child: String) {
        this._file = File(file._file, child.pathNormalize())
    }

    val path: String
        get() = _file.path
    val name: String
        get() = _file.name

    abstract fun exists(): Boolean
    abstract fun getParent(): String
    abstract fun getParentFile(): LintFile
    abstract fun canRead(): Boolean
    abstract fun canWrite(): Boolean
    abstract fun isDirectory(): Boolean
    abstract fun isFile(): Boolean

    /**
     * 文件最后修改时间,单位毫秒(与 [java.io.File.lastModified] 语义一致)。
     * 文件不存在或获取失败时返回 0。
     */
    abstract fun lastModified(): Long

    /**
     * 文件大小,单位字节。文件不存在或获取失败时返回 0。
     */
    abstract fun length(): Long

    @Throws(IOException::class)
    abstract fun createNewFile(): Boolean

    abstract fun delete(): Boolean

    /**
     * 递归删除文件或整个目录树。
     *
     * 与 [delete] 不同,该操作会删除目录内的所有内容(包括子目录),
     * 调用前请确保目标正确,谨慎使用。
     */
    abstract fun deleteRecursively(): Boolean

    abstract fun list(): Array<String>
    abstract fun list(filter: (String) -> Boolean): Array<String>
    abstract fun listFiles(): Array<LintFile>
    abstract fun listFiles(filter: (LintFile) -> Boolean): Array<LintFile>

    /**
     * 一次性批量获取所有子项的名称、大小、修改时间与类型。
     *
     * 遍历大目录(如 app 私有目录)时,性能远优于逐项调用
     * [isDirectory]/[length]/[lastModified],因为只发起一次底层调用。
     */
    abstract fun listFilesWithAttributes(): Array<LintFileInfo>

    abstract fun mkdirs(): Boolean
    abstract fun renameTo(dest: String): Boolean

    override fun compare(o1: LintFile, o2: LintFile): Int = o1._file.compareTo(o2._file)

    override fun equals(other: Any?): Boolean =
        other is LintFile && other.path == path

    override fun hashCode(): Int = path.hashCode()

    override fun toString(): String = path
}
