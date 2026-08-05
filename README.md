# Lint File

[![License](https://img.shields.io/github/license/qbhx224/LintFile)](LICENSE)
[![Version](https://img.shields.io/github/v/release/qbhx224/LintFile?include_prereleases)](https://github.com/qbhx224/LintFile/releases)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.qbhx224/lint-file)](https://central.sonatype.com/artifact/io.github.qbhx224/lint-file/)

一个适用于Android平台的文件操作库 —— kt库。

# 简介

这是一个多功能文件库，它简化了开发人员访问安卓设备磁盘文件的步骤。
它支持基本的Java文件Api，集成了高级文件操作Api，如"ShizukuFile"， 并支持访问权限的自动申请。
开发者不再需要关心适配不同Android版本的新变化，一切都交给LintFile！

# 特性

- [x] 适配Android 7.0~Android 16
- [x] 支持Shizuku文件操作和Shizuku打开文件操作流
- [x] 高性能的Shizuku文件操作：批量目录属性一次 shell 调用返回
- [x] 简单易用的File Api（与java.io.File类似）
- [x] 自动化权限申请
- [x] 支持通过SAF框架访问`/Android/data`目录
- [x] 智能路径检测：自动判断读写能力并选择最优文件操作模式
- [x] 支持访问任意应用 `/Android/data/<包名>` 私有目录（Shizuku模式）
- [x] 支持递归删除目录树与批量获取目录属性

# 将Lint File导入你的项目

1. 给你的项目配置maven仓库
    ```kotlin
    repositories {
        google()
        mavenCentral()
    }
    ```

2. 导入lint-file依赖
   ```kotlin
   dependencies {
       implementation("io.github.qbhx224:lint-file:2.3.0")
   }
   ```

3. 初始化文件操作库.
   ```kotlin
   class MainActivity : ComponentActivity() {
   
       override fun onCreate(savedInstanceState: Bundle?) {
           super.onCreate(savedInstanceState)
   
           // 初始化Android上下文
           LintFileConfiguration.instance.init(this)
   
           // 进行其它操作
   
       }
   
       override fun onDestroy() {
           // 释放文件操作库
           LintFileConfiguration.instance.destroy()
           super.onDestroy()
       }
   }
   ```

4. 配置访问模式（可选，默认 NORMAL）
   ```kotlin
   // 通过 LintFileConfig 指定 IO 模式
   LintFileConfiguration.instance.init(
       this,
       LintFileConfig(ioModel = IoModel.SHIZUKU) // NORMAL(默认) / SHIZUKU
   )

   // SHIZUKU 模式可访问任意应用的 /Android/data 私有目录
   // 检查 Shizuku 是否可用,未授权时发起授权
   if (!ShizukuUtil.isShizukuAvailable()) {
       ShizukuUtil.requestPermission()
   }
   ```

5. 开始使用
   * 自动化权限
      ```kotlin
      // 1. 先获取LintFile实例
      // file扩展函数会根据文件路径自动创建合适的LintFile实例
      // 你也可以手动创建LintFile的不同实现：DefaultFile、StorageAccessFrameworkFile和ShizukuFile
      val lintFile = file("/xxx/xxx/xxx")
   
      // 2. 通过use扩展函数进行自动化权限申请
      lintFile.use(
          // 实现权限注册，这里只是简单实现，实际上你可以在此弹出模态框来进一步优化交互体验
          onRequestPermission = { type: PermissionType ->
              when (type) {
                  // 外部存储权限
                  PermissionType.EXTERNAL_STORAGE -> {
                      ActivityCompat.requestPermissions(
                          activity,
                          arrayOf(
                              Manifest.permission.WRITE_EXTERNAL_STORAGE,
                              Manifest.permission.READ_EXTERNAL_STORAGE,
                          ),
                          0x000001
                      )
                  }
                  // 所有文件访问权限
                  PermissionType.MANAGE_STORAGE -> {
                      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                          val intent =
                              Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                          intent.data = Uri.parse("package:" + activity.packageName)
                          activity.startActivity(intent)
                      }
                  }
                   // SAF框架文件访问权限
                   PermissionType.STORAGE_ACCESS_FRAMEWORK -> {
                       activity.requestAccessPermission(0x000002, lintFile.path)
                   }
                  // Shizuku权限
                  PermissionType.SHIZUKU -> try {
                      ShizukuUtil.requestPermission()
                  } catch (e: Exception) {
                      e.printStackTrace()
                      Toast.makeText(
                          activity,
                          R.string.text_shizuku_service_is_not_active,
                          Toast.LENGTH_SHORT
                      ).show()
                  }
              }
          },
          // 此回调作用域中表示已获取到需要的权限，可通过this: LintFile来调用文件操作API
          granted = {
              val fileName = this.name
              println(fileName)
          }
      )
      ```
      > **提示**: 权限授予完成后需要**重新调用** `use()` 才会进入 `granted` 作用域,建议在权限回调中重新触发业务流程。
       不要忘了！
      ```kotlin
      class MainActivity : ComponentActivity() {
   
          // 在此保存/Android/data的文件访问权限！
          @Suppress("DEPRECATION")
          override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
              super.onActivityResult(requestCode, resultCode, data)
              takePersistableUriPermission(0x000002, requestCode, resultCode, data)
          }
      }
      ```
   * 文件API操作（与java.io.File类似，这里就不过多赘述）
      ```kotlin
      // 获取Lint File实例
      val file = file("/x/xx/xxx")
   
      // 获取文件名
      val fileName = file.name
      // 获取文件路径
      val path = file.path
      //获取文件列表
      val fileList = file.list()
      ...
      ```
   
   * 打开IO流
      * 打开输入流
         ```kotlin
         //获取LintFile实例
         val file = file("/x/xx/xxx")
         // 打开输入流
         val inputStream = file.openInputStream()
         // 进行读取操作，需要手动关闭流
         ...
         ```
      * 打开输出流
         ```kotlin
         //获取LintFile实例
         val file = file("/x/xx/xxx")
         // 打开输出流
         val outputStream = file.openOutputStream()
         // 进行写入操作，需要手动关闭流
         ...
         ```

   * 批量文件操作
      ```kotlin
      // 一次调用获取目录全部子项的名称/大小/修改时间/类型
      // Shizuku 模式下仅发起一次 shell 调用,遍历大目录性能远超逐项查询
      val infos = file("/sdcard/Android/data/com.example").listFilesWithAttributes()
      infos.forEach { info ->
          println("${info.name} ${info.size} ${info.lastModified} ${info.isDirectory}")
      }

      // 递归删除整个目录树(与 delete() 不同,会删除目录内所有内容,请谨慎使用)
      val deleted = file("/sdcard/Android/data/com.example").deleteRecursively()
      ```

# 注意事项

- **访问 `/Android/data` 目录**（Android 11+ 默认限制）：
  - **NORMAL 模式**：部分设备可通过零宽连接符路径绕过系统拦截，无需任何授权；此方式依赖设备系统实现，可能随系统版本失效，不建议作为唯一方案
  - **SHIZUKU 模式**：可可靠访问任意应用的 `/Android/data/<包名>` 目录并完成增删改查，推荐用于该场景
- **Android 16**：`所有文件访问权限`（MANAGE_EXTERNAL_STORAGE）改为**安装时授予**，运行期跳转设置页不再生效；如需访问共享存储，请在安装应用时勾选
- **`delete()` 与 `deleteRecursively()`**：`delete()` 与 `java.io.File` 语义一致，仅删除文件或空目录；递归删除整个目录树请显式调用 `deleteRecursively()`

# 更新日志

## v2.3.2

- **修复**: Shizuku 13.1.5 将 `Shizuku.newProcess` 改为 private,消费者显式依赖 13.1.5 覆盖库的 13.1.0 时,运行时调用私有方法抛出 `IllegalAccessError` 崩溃
  - 修复: 库升级至 Shizuku **13.1.5**,`newProcess` 改为反射调用(`getDeclaredMethod` + `setAccessible`),兼容 13.1.0(public)与 13.1.5+(private)两种形态;若未来 API 移除该方法,抛出带明确信息的 `IOException`
- **注意**: 若你的项目显式依赖 Shizuku,请使用 `dev.rikka.shizuku:api:13.1.5+`,避免与库产生版本冲突

## v2.3.1

- **修复**: Shizuku 模式无法访问 `/Android/data` 受限目录（如属主为 app 自身 uid、权限 770/700 的深层目录，表现为"目录为空"）
  - 原因：FUSE 对 `/Android/data` 的拦截按路径生效（对 shell 用户同样生效），此前 `safeArg()`、`delete()`/`deleteRecursively()`、FIFO 流均会剥离路径中的零宽连接符，恰好破坏了绕过
  - 修复：Shizuku shell 命令保留零宽伪装路径，原理与 MT 管理器等工具一致；增删改查已在设备实测全部通过
- **补充说明**：`/Android/data` 的 FUSE 拦截对任何调用方（应用或 shell）均按路径匹配，零宽连接符伪装路径可绕过拦截直达底层；NORMAL 与 SHIZUKU 模式均适用

## v2.3.0

- **包名迁移**: 全部源码包由 `io.github.lumkit.io` 迁移至 `io.github.qbhx224.lintfile.io`,库 namespace 与应用包名同步更新
- **破坏性变更**: 旧包名下的 `import io.github.lumkit.io.*` 需替换为 `io.github.qbhx224.lintfile.io.*`
- 新增 `NOTICE` 文件并保留原作者版权信息,符合 LGPL v2.1 修改版分发要求

## v2.2.0

**修复**
- Shizuku 模式 `lastModified()` 返回秒级时间戳未转为毫秒,导致显示 1970-01-21 等错误日期
- `delete()` 曾使用 `rm -rf` 递归删除,违反 `java.io.File.delete()` 语义;现仅删除文件/空目录,递归删除需显式调用 `deleteRecursively()`
- FIFO 流改用真正的 `mkfifo` 管道(此前为普通文件,读取端可能在写入前读到 EOF 导致数据丢失),并支持残留 FIFO 自动清理
- 共享 shell 不再因等待锁超时被强制杀死(此前大文件传输超过 10s 会被并发调用中断,导致传输损坏)
- 大文件传输命令超时从 30s 提升至 30 分钟,`close()` 不再永久阻塞
- `lastModified()`/`length()` 失败返回值统一为 0(与 `java.io.File` 一致)

**新增**
- `listFilesWithAttributes()` 批量属性接口:一次 `ls -la` 返回整目录的名称/大小/修改时间/类型
- `deleteRecursively()` 显式递归删除 API
- 单元测试:命令转义、路径解析、`ls` 输出解析、时间戳换算等 30 个用例

**改进**
- 零宽连接符绕过探测改为子树根 `list()` 校验(比 `canRead` 可靠,部分设备 canRead 为 true 但实际仍被拦截),结果按子树缓存
- `isSafDir()` 探测结果缓存,避免逐文件 syscall;绕过可用时不再强制走 SAF
- 使用 POSIX 单引号转义重构所有 shell 命令,杜绝文件名导致的命令注入
- FIFO 流读写端并行消费,不再死等管道缓冲区;异常路径自动清理临时 FIFO,消除泄漏
- 路径规范化重构:`pathHandle()` 改为幂等规范化,消除零宽连接符重复叠加导致的路径误判
- SAF 授权根重构:记录持久化授权树 URI,消灭硬编码目录层级,支持 SD 卡等任意卷
- API 对齐:`list()`/`list(filter)` 改为返回纯文件名,与 `java.io.File` 语义一致
- 权限智能判断:应用专属目录直通无需权限;Android 11+ 正确区分权限类型
- 生命周期改进:`init()` 支持任意 `Context`;Shizuku 监听器注册去重
- `LintFile` 增加 `equals()`/`hashCode()`/`toString()`

## v2.1.1

- 新增 `useSaf` 标志，支持在有SAF权限时强制使用SAF模式
- 改进 `isSafDir()` 逻辑，增加 `canWrite()` 检测，智能判断是否需要SAF
- `DefaultFile.delete()` 和 `renameTo()` 增加 `stripHiddenChar()` 兜底机制
- `takePersistableUriPermission()` 授权成功后自动启用SAF模式
- 适配Android 16

## v2.0.0

- 移除SU/KSU/SUU支持
- 重写Shell管理架构，修复竞态条件和无限阻塞问题
- 改进Shizuku兼容性
- 修复内存泄漏问题

# 作者

- **原作者**：[lumkit](https://github.com/lumkit)
- **二改作者**：[qbhx224](https://github.com/qbhx224)

# 许可证

本项目基于 [GNU LGPL v2.1](LICENSE) 许可证开源。
