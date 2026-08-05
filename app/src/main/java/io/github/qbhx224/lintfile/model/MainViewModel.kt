package io.github.qbhx224.lintfile.model

import android.os.Environment
import androidx.lifecycle.ViewModel
import io.github.qbhx224.lintfile.io.LintFile
import io.github.qbhx224.lintfile.io.file
import io.github.qbhx224.lintfile.io.impl.DefaultFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel: ViewModel() {

    val hasManageExternalStorage = MutableStateFlow(false)

    private val _currentFile: MutableStateFlow<LintFile> = MutableStateFlow(DefaultFile(Environment.getExternalStorageDirectory().absolutePath))
    val currentFile = _currentFile.asStateFlow()

    fun pop(onTop: () -> Unit = {}) {
        val path = _currentFile.value.getParent()
        if (path.isEmpty()) {
            onTop()
            return
        }
        _currentFile.value = file(path)
    }

    fun push(file: LintFile) {
        _currentFile.value = file(file.path)
    }
}