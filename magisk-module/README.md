# RecordYou 保活模块 (Magisk)

基于 [you-apps/RecordYou-Magisk-Module](https://github.com/you-apps/RecordYou-Magisk-Module) 修改，合并了两个功能：

1. **解锁内部音频录制**：把 RecordYou 装成系统特权应用（`/system/priv-app`），配合
   `system/etc/permissions/privapp-permissions-recordyou.xml` 白名单获得
   `CAPTURE_AUDIO_OUTPUT` 权限（"录制系统内部音频"用的就是这个权限，普通安装的
   APK 永远申请不到）。
2. **防止被杀后台**：`service.sh` 在开机时（late_start service 阶段）自动执行电量
   策略白名单、后台运行权限、待机分桶限制等一系列命令，解除 App 被系统限制的可能。
   App 本身**不需要 root、也不会弹任何授权请求**——一个录音录屏软件伸手要 root 说不
   过去，所以这部分全部放在模块脚本里做，模块本身在开机时已经是以 root 身份运行的。

## 这个模块是怎么工作的

`system/priv-app/RecordYou/RecordYou.apk` 不是真正的 RecordYou APK，只是一个只有
包名（`com.bnyro.recorder`）、没有任何代码的占位包（源码见仓库根目录的
`:magisk-placeholder` 模块）。原理：Android 对"曾经是系统应用"的包，即使之后用普通
方式（侧载/正常安装升级）覆盖成新版本，只要包名不变，仍然保留特权权限资格。

所以流程是：**只需要刷一次这个模块**，把占位包"种"进系统分区；之后正常安装/升级
真正的 RecordYou（release APK）就会继承特权状态，不需要每次发新版本都重新刷模块。

## 重要：只对 release 包生效，且换版本前要先手动清掉旧的

`app/build.gradle.kts` 里 debug 构建有 `applicationIdSuffix = ".debug"`，最终包名是
`com.bnyro.recorder.debug`，跟这个模块授权的 `com.bnyro.recorder`（release 包名）
是两个不同的包，装 debug 包永远拿不到这个模块给的权限。不过现在 CI 里，不管是
push 触发的私密测试构建，还是打 tag 触发的正式发布，编译的都是同一个 **release**
包（`assembleRelease`），不再用 `.debug` 那份——也就是说日常 push 出来的测试包一样
能测出内部音频、防杀后台这些特权功能，不用非得等打 tag。两者的区别只是发布方式：
push 发到一个固定的 `test-build-latest` **draft**（草稿）Release，只有仓库协作者能
看到；打 tag 发到一个正常公开的 Release。

另外，这个项目没有配置正式签名 keystore（release 包用的是 CI 每次运行时临时生成
的调试签名，见 `app/build.gradle.kts` 里 release 的 `signingConfig`）。这意味着
**不同批次的构建之间签名并不一致**（不管是两次 push 之间，还是 push 和 tag 之间）：
Android 要求"覆盖安装升级"必须用同一把签名，所以如果你已经装了某一次构建的
release apk，再想装另一次构建的，大概率会遇到"签名不一致，安装失败"。解决方法
很简单：**先卸载旧版本 App（或者卸载模块后重启，彻底清掉系统里的占位包），再安装
新版本**，当成一次全新安装，而不是覆盖升级。这个项目仅供已 root 的个人/小范围
使用，不考虑无缝升级，所以没有再去管理一把正式 keystore。

## 模块内容如何生成

- `module.prop` / `install.sh` / `service.sh` / `apply-keepalive.sh` / `action.sh` /
  `system/etc/permissions/*`：仓库里直接维护的静态文件（`service.sh` 和
  `apply-keepalive.sh` 源码放在 `common/` 目录下，`install.sh` 刷入时会自动把它们
  摊平到模块根目录，这是 Magisk 运行时期望的位置，`common/` 只是仓库里归档用的
  文件夹名）
- `system/priv-app/RecordYou/RecordYou.apk`：由 CI（push 或打 tag 时）用
  `:magisk-placeholder` 子模块现编现放（`.gitkeep` 只是让空目录能被 git 跟踪，
  实际这个 apk 不入库）。它和同一次 CI 里编出来的 release apk 用的是**同一台
  运行机器当场生成的调试签名**，所以刷完模块后的第一次安装通常能直接装上去。

## 手动重新执行保活命令

刷入模块并重启一次之后，打开 Magisk App，这个模块的卡片上会多出一个 **Action**
按钮（首次刷入后必须重启一次这个按钮才会出现，这是 Magisk 本身的机制，不是这个
模块的问题）。如果你手动改动过电量白名单之类的设置、不想等下次重启才恢复，点一下
这个按钮就能立刻重新执行一遍 `apply-keepalive.sh`，并直接在 Magisk App 里看到中文
的执行结果。
