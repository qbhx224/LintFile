// Modified from io.github.lumkit.io (LintFile, LGPL v2.1), original author: lumkit.
// Modified by qbhx224 on 2026-08-05. See README.md and NOTICE.
package io.github.qbhx224.lintfile.io

/**
 * 批量列目录时的文件条目信息。
 *
 * 由 [LintFile.listFilesWithAttributes] 一次性返回,避免对每个子项单独发起
 * 底层调用(Shizuku 模式下为一次 shell 命令)。
 *
 * 注意:Shizuku 模式下条目信息来自 `ls -la` 输出,
 * 含换行符的文件名无法解析;时间精度为分钟级。
 */
data class LintFileInfo(
    val name: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val isFile: Boolean
)
