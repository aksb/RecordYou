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

## 重要：只对 release 包生效

`app/build.gradle.kts` 里 debug 构建有 `applicationIdSuffix = ".debug"`，最终包名是
`com.bnyro.recorder.debug`，跟这个模块授权的 `com.bnyro.recorder`（release 包名）
是两个不同的包，装 debug 包永远拿不到这个模块给的权限。不过现在 CI 里，不管是
push 触发的私密测试构建，还是打 tag 触发的正式发布，编译的都是同一个 **release**
包（`assembleRelease`），不再用 `.debug` 那份——也就是说日常 push 出来的测试包一样
能测出内部音频、防杀后台这些特权功能，不用非得等打 tag。两者的区别只是发布方式：
push 的产物进 Actions 的 Artifact（保留 14 天，只有你自己能下载），打 tag 的产物发到
公开的 Release 页面。

签名方面：release 包和本模块里的占位包现在都用仓库根目录那把固定的正式钥匙
`RecordYou.jks`（密码走 GitHub Secrets 注入，见 `.github/workflows/ci.yml`），所以同一把
钥匙出的正式包之间可以正常覆盖升级，模块只需刷一次。

只有一点例外：改造前用 CI 临时调试签名发出去的旧 APK，以及旧模块 zip 里的占位包，
都是另一把旧钥匙。这种情况下**光卸载 App 不够**——`/system/priv-app` 里的占位包仍是旧
签名，系统会拿它跟新 APK 比对，照样装不上。正确顺序是：先刷一次新的模块 zip（把占位包
换成新签名）→ 重启 → 再安装新的 APK。换过来之后，后续升级就不用再管这套了。

## 模块内容如何生成

- `module.prop` / `install.sh` / `service.sh` / `apply-keepalive.sh` / `action.sh` /
  `system/etc/permissions/*`：仓库里直接维护的静态文件（`service.sh` 和
  `apply-keepalive.sh` 源码放在 `common/` 目录下，`install.sh` 刷入时会自动把它们
  摊平到模块根目录，这是 Magisk 运行时期望的位置，`common/` 只是仓库里归档用的
  文件夹名）
- `system/priv-app/RecordYou/RecordYou.apk`：由 CI 在打 tag 时用
  `:magisk-placeholder` 子模块现编现放（`.gitkeep` 只是让空目录能被 git 跟踪，
  实际这个 apk 不入库）。它和同一次 CI 里编出来的 release apk 用的是**同一把**
  正式签名，所以刷完模块后安装正式版可以直接覆盖上去。

## 手动重新执行保活命令

刷入模块并重启一次之后，打开 Magisk App，这个模块的卡片上会多出一个 **Action**
按钮（首次刷入后必须重启一次这个按钮才会出现，这是 Magisk 本身的机制，不是这个
模块的问题）。如果你手动改动过电量白名单之类的设置、不想等下次重启才恢复，点一下
这个按钮就能立刻重新执行一遍 `apply-keepalive.sh`，并直接在 Magisk App 里看到中文
的执行结果。
