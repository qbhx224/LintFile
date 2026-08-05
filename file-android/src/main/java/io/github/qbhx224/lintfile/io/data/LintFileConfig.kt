// Modified from io.github.lumkit.io (LintFile, LGPL v2.1), original author: lumkit.
// Modified by qbhx224 on 2026-08-05. See README.md and NOTICE.
package io.github.qbhx224.lintfile.io.data

import kotlinx.serialization.Serializable

@Serializable
data class LintFileConfig(
    val ioModel: IoModel = IoModel.NORMAL
)
