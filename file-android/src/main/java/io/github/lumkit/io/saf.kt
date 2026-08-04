package io.github.lumkit.io

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

internal val androidPath = File(Environment.getExternalStorageDirectory(), "/Android").absolutePath
internal val rootPath = "${Environment.getExternalStorageDirectory().absolutePath}/"

internal fun String.checkPath() {
    if (!this.stripHiddenChar().startsWith(androidPath)) {
        throw RuntimeException("Invalid path! Please ensure the path is under ${androidPath}.")
    }
}

/**
 * 提取路径所属卷根,如 /storage/emulated/0、/storage/XXXX-XXXX
 */
internal fun String.volumeRoot(): String? {
    val pure = stripHiddenChar()
    if (!pure.startsWith("/storage/")) return null
    val idx = pure.indexOf('/', "/storage/".length)
    return if (idx < 0) pure else pure.substring(0, idx)
}

/**
 * 计算 SAF 授权根(树)的绝对路径:
 * 优先定位到 /Android/data/<包名>,其次 /Android,最后卷根。
 * 取代旧实现中硬编码目录层级的方式,支持 SD 卡等任意卷。
 */
internal fun String.authorityRootPath(): String {
    val pure = stripHiddenChar()
    val root = pure.volumeRoot() ?: return pure
    val marker = "/Android/data/${LintFileConfiguration.instance.context.packageName}"
    val markerIdx = pure.indexOf(marker)
    if (markerIdx >= 0) {
        return pure.substring(0, markerIdx + marker.length)
    }
    val androidIdx = pure.indexOf("/Android")
    if (androidIdx >= 0) {
        val end = pure.indexOf('/', androidIdx + "/Android".length)
        return if (end < 0) pure else pure.substring(0, end)
    }
    return root
}

/**
 * 路径相对授权根的剩余部分
 */
internal fun String.primaryChildPath(): String {
    val pure = stripHiddenChar()
    val root = authorityRootPath()
    return when {
        pure == root -> ""
        pure.startsWith("$root/") -> pure.substring(root.length)
        else -> pure
    }
}

/**
 * 绝对路径映射为 DocumentsProvider 的 document id,
 * 主卷使用 primary: 前缀,SD 卡等使用卷 id 前缀
 */
internal fun String.folderId(hide: Boolean = true): String {
    val pure = stripHiddenChar()
    val root = pure.volumeRoot()
        ?: throw RuntimeException("Invalid path! Please ensure the path is under /storage.")
    val relative = pure.substring(root.length).trimStart('/')
    val prefix = if (root == Environment.getExternalStorageDirectory().absolutePath) {
        "primary"
    } else {
        root.substringAfterLast('/')
    }
    return "$prefix:${relative.pathHandle(hide)}"
}

fun String.uri(hide: Boolean = true): Uri =
    Uri.Builder()
        .scheme("content")
        .authority("com.android.externalstorage.documents")
        .appendPath("tree")
        .appendPath(this.folderId(hide))
        .build()

fun String.documentUri(hide: Boolean = true): Uri =
    Uri.Builder()
        .scheme("content")
        .authority("com.android.externalstorage.documents")
        .appendPath("tree")
        .appendPath(this.folderId(hide))
        .appendPath("document")
        .appendPath(this.folderId(hide))
        .build()

fun String.getPrivateRootPath(): String = authorityRootPath()

/**
 * 授权根对应的 tree+document 复合 URI,用于 SAF 授权选择器的初始定位
 */
fun String.documentUriForPermissions(hide: Boolean = true): Uri {
    val rootPath = authorityRootPath()
    val builder = Uri.Builder()
        .scheme("content")
        .authority("com.android.externalstorage.documents")
        .appendPath("tree")
        .appendPath(rootPath.folderId(hide))
        .appendPath("document")
        .appendPath(rootPath.folderId(hide))
    return builder
        .build()
}

/**
 * 构造访问指定路径所需的 tree+document 复合 URI。
 * tree 段使用授权根,document 段使用完整目标路径,
 * 该复合 URI 可直接交给 DocumentFile.fromTreeUri 使用。
 */
fun String.documentReallyUri(hide: Boolean = false): Uri {
    val treeDocId = authorityRootPath().folderId(false)
    val docId = folderId(hide)
    return Uri.Builder()
        .scheme("content")
        .authority("com.android.externalstorage.documents")
        .appendPath("tree")
        .appendPath(treeDocId)
        .appendPath("document")
        .appendPath(docId)
        .build()
}

fun String.documentFileUri(tree: Boolean = true, hide: Boolean = true): Uri =
    DocumentsContract.buildTreeDocumentUri(
        "com.android.externalstorage.documents",
        this.folderId(hide)
    )

fun Activity.requestAccessPermission(requestCode: Int, path: String) {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    intent.setFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION
                or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    )
    val uri = path.documentUriForPermissions()
    intent.putExtra("android.provider.extra.INITIAL_URI", uri)
    intent.putExtra("pn", LintFileConfiguration.instance.context.packageName)
    startActivityForResult(intent, requestCode)
}

@SuppressLint("WrongConstant")
fun Activity.takePersistableUriPermission(
    yourCode: Int,
    requestCode: Int,
    resultCode: Int,
    data: Intent?
) {
    if (resultCode == Activity.RESULT_OK && yourCode == requestCode && data != null) {
        data.data?.let {
            contentResolver.takePersistableUriPermission(
                it, data.flags and (
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
            )
            LintFileConfiguration.instance.safTreeUri = it
            LintFileConfiguration.instance.useSaf = true
        }
    }
}

/**
 * 判断当前 uri 是否已被持久化授权覆盖。
 * 通过比较 document id 前缀判断,目标属于任一已授权树即视为已授权。
 */
fun Uri.isInPersistedUriPermissions(): Boolean {
    val mine = toString().replace("%E2%80%8D", "")
    val mineDoc = mine.substringAfterLast("/document/", mine.substringAfterLast("/tree/", ""))
    return LintFileConfiguration.instance.context.contentResolver.persistedUriPermissions.any { perm ->
        val theirs = perm.uri.toString().replace("%E2%80%8D", "")
        val theirDoc = theirs.substringAfterLast("/document/", theirs.substringAfterLast("/tree/", ""))
        mineDoc.startsWith(theirDoc) && (perm.isReadPermission || perm.isWritePermission)
    }
}

/**
 * 将 document uri 或 tree uri 解析为绝对路径,支持 primary 与 SD 卡卷
 */
fun Uri.absolutePath(): String {
    val decode = Uri.decode(toString())
    val docId = decode.substringAfterLast("/document/", decode.substringAfterLast("/tree/", ""))
    val colon = docId.indexOf(':')
    if (colon <= 0) return decode
    val volumeId = docId.substring(0, colon)
    val relative = docId.substring(colon + 1)
    val root = if (volumeId == "primary") {
        Environment.getExternalStorageDirectory().absolutePath
    } else {
        File(Environment.getExternalStorageDirectory().parentFile, volumeId).absolutePath
    }
    return File(root, relative).absolutePath
}
