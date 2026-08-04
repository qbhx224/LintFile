package io.github.lumkit.io

import io.github.lumkit.io.impl.buildDeleteCommand
import io.github.lumkit.io.impl.buildDeleteRecursivelyCommand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class IoUtilsTest {

    @Test
    fun escapeShellArg_basic() {
        assertEquals("'abc'", "abc".escapeShellArg())
    }

    @Test
    fun escapeShellArg_withSingleQuote() {
        assertEquals("'a'\\''b'", "a'b".escapeShellArg())
    }

    @Test
    fun escapeShellArg_injectionAttempt() {
        val cmd = "'; rm -rf /; echo '"
        assertEquals(
            "''\\''; rm -rf /; echo '\\'''",
            cmd.escapeShellArg()
        )
    }

    @Test
    fun escapeShellArg_empty() {
        assertEquals("''", "".escapeShellArg())
    }

    @Test
    fun stripHiddenChar_removesZwj() {
        assertEquals("Android/data", "Android\u200D/data".stripHiddenChar())
        assertEquals("/sdcard/a.txt", "/sdcard/a.txt".stripHiddenChar())
    }

    @Test
    fun lockedSubtreeRoot_dataPackage() {
        assertEquals(
            "/storage/emulated/0/Android/data/com.example.app",
            "/storage/emulated/0/Android/data/com.example.app/files/a.txt".lockedSubtreeRoot()
        )
    }

    @Test
    fun lockedSubtreeRoot_dataPackageExactDir() {
        assertEquals(
            "/storage/emulated/0/Android/data/com.example.app",
            "/storage/emulated/0/Android/data/com.example.app".lockedSubtreeRoot()
        )
    }

    @Test
    fun lockedSubtreeRoot_obb() {
        assertEquals(
            "/storage/emulated/0/Android/obb",
            "/storage/emulated/0/Android/obb/xxx.obb".lockedSubtreeRoot()
        )
    }

    @Test
    fun lockedSubtreeRoot_mediaIsNotLocked() {
        assertNull("/storage/emulated/0/Android/media/a.mp3".lockedSubtreeRoot())
    }

    @Test
    fun lockedSubtreeRoot_nonAndroidIsNull() {
        assertNull("/sdcard/Download/a.txt".lockedSubtreeRoot())
        assertNull("/storage/emulated/0/Android".lockedSubtreeRoot())
    }

    @Test
    fun parseStatSecondsToMillis_convertsSecondsToMillis() {
        assertEquals(1_785_820_297_000L, "1785820297".parseStatSecondsToMillis())
    }

    @Test
    fun parseStatSecondsToMillis_trimsWhitespace() {
        assertEquals(1_785_820_297_000L, " 1785820297 \n".parseStatSecondsToMillis())
    }

    @Test
    fun parseStatSecondsToMillis_invalidReturnsZero() {
        assertEquals(0L, "".parseStatSecondsToMillis())
        assertEquals(0L, "abc".parseStatSecondsToMillis())
        assertEquals(0L, "-".parseStatSecondsToMillis())
    }

    @Test
    fun parseStatSize_parsesBytes() {
        assertEquals(3452L, "3452".parseStatSize())
        assertEquals(0L, "".parseStatSize())
        assertEquals(0L, "abc".parseStatSize())
    }

    @Test
    fun parseLsOutput_filtersDotEntriesAndEmpties() {
        assertArrayEquals(
            arrayOf("a", "b c"),
            ".\n..\na\nb c".parseLsOutput()
        )
    }

    @Test
    fun parseLsOutput_empty() {
        assertArrayEquals(arrayOf(), "".parseLsOutput())
        assertArrayEquals(arrayOf(), ".\n..\n".parseLsOutput())
    }

    @Test
    fun parseLsOutput_singleFile() {
        assertArrayEquals(arrayOf("README.md"), "README.md".parseLsOutput())
    }

    @Test
    fun buildDeleteCommand_matchesJavaFileSemantics() {
        val path = "/sdcard/Download/a'b"
        val arg = path.escapeShellArg()
        assertEquals(
            "[ -e $arg ] && (rm -f $arg || rmdir $arg) && echo 1 || echo 0",
            buildDeleteCommand(path)
        )
    }

    @Test
    fun buildDeleteCommand_neverRecursive() {
        assertFalse(buildDeleteCommand("/sdcard/a").contains("-rf"))
    }

    @Test
    fun buildDeleteCommand_injectionSafe() {
        val cmd = buildDeleteCommand("/sdcard/';rm -rf /;")
        assertTrue(cmd.contains("'\\'';rm"))
        assertTrue(cmd.endsWith("echo 1 || echo 0"))
    }

    @Test
    fun buildDeleteRecursivelyCommand_matches() {
        val path = "/sdcard/Download/target"
        val arg = path.escapeShellArg()
        assertEquals("[ -e $arg ] && rm -rf $arg && echo 1 || echo 0", buildDeleteRecursivelyCommand(path))
        assertTrue(buildDeleteRecursivelyCommand(path).contains("rm -rf"))
    }

    @Test
    fun parseLsLaOutput_regularFile() {
        val expected = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
            .parse("2026-08-04 14:41")!!.time
        val line = "-rw-rw---- 1 u0_a210 media_rw    2 2026-08-04 14:41 report.txt"
        val info = line.parseLsLaLine()
        assertEquals(LintFileInfo("report.txt", 2, expected, false, true), info)
    }

    @Test
    fun parseLsLaLine_directory() {
        val line = "drwxrws--- 2 u0_a210 media_rw 3452 2026-08-04 14:41 subdir"
        val info = line.parseLsLaLine()!!
        assertTrue(info.isDirectory)
        assertFalse(info.isFile)
        assertEquals(3452L, info.size)
        assertEquals("subdir", info.name)
    }

    @Test
    fun parseLsLaLine_nameWithSpaces() {
        val line = "-rw-rw---- 1 u0_a210 media_rw 2 2026-08-04 14:41 my file.txt"
        assertEquals("my file.txt", line.parseLsLaLine()?.name)
    }

    @Test
    fun parseLsLaLine_hiddenAndOldFiles() {
        val old = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
            .parse("2020-01-01 10:30")!!.time
        val hidden = "-rw-rw---- 1 u0_a210 media_rw 0 2020-01-01 10:30 .hidden".parseLsLaLine()!!
        assertEquals(".hidden", hidden.name)
        assertEquals(old, hidden.lastModified)
    }

    @Test
    fun parseLsLaLine_yearOnlyFallback() {
        val expected = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
            .parse("2020-01-01")!!.time
        val info = "-rw-rw---- 1 u0_a210 media_rw 0 2020-01-01 2020 legacy.txt".parseLsLaLine()!!
        assertEquals("legacy.txt", info.name)
        assertEquals(expected, info.lastModified)
    }

    @Test
    fun parseLsLaLine_symlinkNameTrimmed() {
        val line = "lrwxrwxrwx 1 root root 6 2026-08-04 14:41 link -> report.txt"
        val info = line.parseLsLaLine()!!
        assertEquals("link", info.name)
        assertFalse(info.isDirectory)
        assertFalse(info.isFile)
    }

    @Test
    fun parseLsLaOutput_skipsTotalAndDotEntries() {
        val raw = """
            total 11
            drwxrws--- 2 u0_a210 media_rw 3452 2026-08-04 14:41 .
            drwxrws--- 2 u0_a210 media_rw 3452 2026-08-04 14:41 ..
            -rw-rw---- 1 u0_a210 media_rw    3 2026-08-04 14:41 report.txt
        """.trimIndent()
        val infos = raw.parseLsLaOutput()
        assertEquals(1, infos.size)
        assertEquals("report.txt", infos[0].name)
    }

    @Test
    fun parseLsLaOutput_emptyDir() {
        assertEquals(0, "total 0".parseLsLaOutput().size)
        assertEquals(0, "".parseLsLaOutput().size)
    }

    @Test
    fun parseLsLaLine_invalidLines() {
        assertNull("".parseLsLaLine())
        assertNull("total 11".parseLsLaLine())
        assertNull("short line".parseLsLaLine())
        assertNull("-rw-rw---- 1 u0_a210 media_rw abc 2026-08-04 14:41 x.txt".parseLsLaLine())
    }
}
