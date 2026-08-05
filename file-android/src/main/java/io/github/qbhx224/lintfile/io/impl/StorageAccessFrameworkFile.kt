// Modified from io.github.lumkit.io (LintFile, LGPL v2.1), original author: lumkit.
// Modified by qbhx224 on 2026-08-05. See README.md and NOTICE.
package io.github.qbhx224.lintfile.io.impl

import androidx.documentfile.provider.DocumentFile
import io.github.qbhx224.lintfile.io.LintFile
import io.github.qbhx224.lintfile.io.LintFileConfiguration
import io.github.qbhx224.lintfile.io.LintFileInfo
import io.github.qbhx224.lintfile.io.authorityRootPath
import io.github.qbhx224.lintfile.io.documentReallyUri
import io.github.qbhx224.lintfile.io.primaryChildPath
import io.github.qbhx224.lintfile.io.stripHiddenChar
import java.io.File
import java.io.FileNotFoundException

class StorageAccessFrameworkFile : LintFile {

    constructor(path: String) : super(path)
    constructor(file: LintFile) : super(file)
    constructor(file: LintFile, child: String) : super(file, child)

    private val context = LintFileConfiguration.instance.context
    internal val documentFile: DocumentFile? = DocumentFile.fromTreeUri(context, path.stripHiddenChar().documentReallyUri(false))

    override fun exists(): Boolean =
        this.documentFile?.exists() ?: false

    override fun getParent(): String = this._file.parent?.stripHiddenChar() ?: ""

    override fun getParentFile(): LintFile = StorageAccessFrameworkFile(getParent())

    override fun canRead(): Boolean =
        this.documentFile?.canRead() ?: false

    override fun canWrite(): Boolean =
        this.documentFile?.canWrite() ?: false

    override fun isDirectory(): Boolean =
        this.documentFile?.isDirectory ?: false

    override fun isFile(): Boolean =
        this.documentFile?.isFile ?: false

    override fun lastModified(): Long =
        this.documentFile?.lastModified() ?: 0

    override fun length(): Long =
        this.documentFile?.length() ?: 0

    override fun createNewFile(): Boolean {
        if (exists())
            return true
        val parentFile = getParentFile() as StorageAccessFrameworkFile
        if (!parentFile.exists()) {
            throw FileNotFoundException("No such file or directory: ${parentFile.path}")
        }
        return parentFile.documentFile?.createFile("*/*", name) != null
    }

    override fun delete(): Boolean =
        this.documentFile?.delete() ?: false

    // DocumentsProvider 的 deleteDocument 对目录递归删除整个子树
    override fun deleteRecursively(): Boolean =
        this.documentFile?.delete() ?: false

    override fun list(): Array<String> {
        val list = ArrayList<String>()
        this.documentFile?.listFiles()?.forEach {
            list.add(it.name ?: "")
        }
        return list.toTypedArray()
    }

    override fun list(filter: (String) -> Boolean): Array<String> =
        this.documentFile?.listFiles()
            ?.map { it.name ?: "" }
            ?.filter { filter(it) }
            ?.toTypedArray() ?: arrayOf()

    override fun listFiles(): Array<LintFile> {
        val list = ArrayList<StorageAccessFrameworkFile>()
        this.documentFile?.listFiles()?.forEach { child ->
            list.add(StorageAccessFrameworkFile(File(path.stripHiddenChar(), child.name ?: "").absolutePath))
        }
        return list.toTypedArray()
    }

    override fun listFiles(filter: (LintFile) -> Boolean): Array<LintFile> =
        listFiles().filter { filter(it) }.toTypedArray()

    override fun listFilesWithAttributes(): Array<LintFileInfo> =
        this.documentFile?.listFiles()?.map {
            LintFileInfo(
                name = it.name ?: "",
                size = it.length(),
                lastModified = it.lastModified(),
                isDirectory = it.isDirectory,
                isFile = it.isFile
            )
        }?.toTypedArray() ?: arrayOf()

    override fun mkdirs(): Boolean {
        if (exists())
            return true

        val childPath = path.primaryChildPath()
        if (childPath.isEmpty())
            return true

        var current = path.authorityRootPath()
        childPath.trimStart('/').split("/").forEach { name ->
            if (name.isNotEmpty()) {
                current = File(current, name).absolutePath
                val safFile = StorageAccessFrameworkFile(current)
                if (!safFile.exists()) {
                    StorageAccessFrameworkFile(File(current).parent!!).documentFile
                        ?.createDirectory(name)
                        ?: throw FileNotFoundException("Cannot write to file $path")
                }
            }
        }
        return StorageAccessFrameworkFile(current).exists()
    }

    override fun renameTo(dest: String): Boolean =
        this.documentFile?.renameTo(File(dest).name) ?: false
}
