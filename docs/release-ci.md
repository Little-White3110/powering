# 发布与 CI

GitHub Actions 流水线：`.github/workflows/release.yml`。**推 tag 即发版**——带上版本号的 tag 推上去，自动跑单测、打包、创建 GitHub Release 并挂上 APK。

## 触发方式

| 触发 | 行为 |
|---|---|
| `git push origin v1.2.3` | 跑单测 → 打 `release` 包 → 自动创建/更新 GitHub Release（正式发布） |
| Actions 页面手动触发 | 填版本号发版；若该 tag 还不存在，流水线会在当前 commit 上补建并推送 tag |
| 推送 Pull Request | 只跑单测 + 构建做校验，**不发版** |

tag 必须匹配 `vX.Y.Z`，可带预发布后缀（`v1.2.3-beta.1`）。带后缀的版本会自动标记为 GitHub 的 Pre-release。

## 版本号怎么进到 APK 里

CI 从 tag 解析出 `versionName`，再按固定公式算出 `versionCode`，通过 Gradle 命令行属性注入，不需要改任何代码：

```
versionCode = major × 1000000 + minor × 1000 + patch
```

| tag | versionName | versionCode |
|---|---|---|
| `v1.2.3` | `1.2.3` | 1002003 |
| `v1.2.3-beta.1` | `1.2.3-beta.1` | 1002003 |
| `v2.0.0` | `2.0.0` | 2000000 |

`versionCode` 与 `versionName` 的默认值写在 [`app/build.gradle.kts`](../HolePowerRing/app/build.gradle.kts)，`-PversionName=` / `-PversionCode=` 未传时使用默认值，所以本地构建行为不变。注意 Android 要求升级安装时 `versionCode` 必须递增，**同一版本号的预发布与正式版共用同一个 versionCode**（比如 `v1.2.3-beta.1` → `v1.2.3`），正式版用户仍需先卸载 beta 版才能装上。

## 发版一次要做什么

发版本身只需推 tag，但默认值与文档要一并回填，避免「本地包显示 1.0.0、线上已是 1.1.0」这类错位：

1. 改 `app/build.gradle.kts` 的 `DEFAULT_VERSION_NAME` / `DEFAULT_VERSION_CODE`（公式同上，如 `1.1.0` → `1001000`）
2. README「更新日志」补一节，并同步「功能」「已知限制」等描述；`AGENTS.md` 里的当前版本号口径
3. 提交并推 `main`，让 PR 校验或本地 `./gradlew test` 先过一遍
4. `git tag -a vX.Y.Z -m "..." && git push origin vX.Y.Z` → Release 流水线自动跑
5. `gh run watch` 盯到出 Release；核对资产 `HolePowerRing-vX.Y.Z.apk` 与 `SHA256SUMS.txt`、以及构建日志里**有没有「使用 debug 签名」的 warning**（见下）

已发版本：`v0.1.0`（2026-10-02）、`v1.1.0`（2026-10-04）。

## 一次性配置：发布密钥

模块的 Release 必须签名才能安装（APK 默认不走 debug 签名）。密钥**不入库**，通过 GitHub Secrets 传给 CI。

> **当前仓库状态（2026-10-04）**：4 个 `RELEASE_*` Secrets **一个都还没配**，所以 v0.1.0 与 v1.1.0 的产物都是 CI 回落出来的 **debug 签名包**（构建日志有 `::warning::未配置 RELEASE_KEYSTORE_BASE64` 这一行）。两者签名一致、可互相覆盖升级；**但一旦改用正式密钥，装过 debug 包的用户必须卸载重装**。要转正式签名前先想清楚这一点。

### 1. 本地生成一个 keystore

```bash
cd HolePowerRing
keytool -genkeypair -v -keystore holepowerring-release.jks \
  -alias holepowerring -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <你的库密码> -keypass <你的 key 密码> \
  -dname "CN=HolePowerRing, OU=Dev, O=Little-White3110, C=CN"
```

> 密钥一旦丢失，**已发布版本无法再被覆盖升级**，只能废弃旧签名改用新签名（用户需卸载重装）。请自行备份，不要只存在 CI Secrets 里。

### 2. 填进仓库 Secrets

在 `Settings → Secrets and variables → Actions → New repository secret` 中新建 4 个：

| Secret 名 | 内容 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | keystore 文件的 base64：`base64 -i holepowerring-release.jks`（Windows：`certutil -encode holepowerring-release.jks holepowerring-release.b64`，去掉首尾行头尾后使用） |
| `RELEASE_KEYSTORE_PASSWORD` | `-storepass` |
| `RELEASE_KEY_ALIAS` | `-alias` |
| `RELEASE_KEY_PASSWORD` | `-keypass` |

### 3. 未配置时的行为

Secrets 缺失（含来自 fork 的 PR）时，构建不会失败：`app/build.gradle.kts` 检测不到完整密钥就**回落到 debug 签名**，产物仍可安装，但日志会打印警告。这种包不应该对外发布。

## 本地复现 release 构建

```bash
cd HolePowerRing

# 仅本机自测（回落 debug 签名，日志会给提示）
./gradlew assembleRelease -PversionName=1.2.3 -PversionCode=1002003

# 用与 CI 相同的密钥正式签名（Windows 用 gradlew.bat）
export RELEASE_STORE_FILE="$PWD/holepowerring-release.jks"
export RELEASE_STORE_PASSWORD='<库密码>'
export RELEASE_KEY_ALIAS='holepowerring'
export RELEASE_KEY_PASSWORD='<key 密码>'
./gradlew assembleRelease -PversionName=1.2.3 -PversionCode=1002003
./gradlew signingReport   # 确认签名用的是预期密钥
```

产物：`app/build/outputs/apk/release/app-release.apk`

## Release 资产

每个 Release 挂两个文件：

- `HolePowerRing-vX.Y.Z.apk` —— 已签名的安装包
- `SHA256SUMS.txt` —— 校验和，手机端下载后可核对完整性

变更日志由 GitHub 自动生成（上一个 tag → 当前 tag 的 commit/PR 列表）。首次发版没有上一个 tag，生成全量日志。

## 常见问题

**推了 tag 但流水线没跑**
tag 必须以 `v` 开头（工作流只监听 `v*`）；另外检查 Actions 页面的触发开关有没有被禁用。

**版本号格式报错**
tag 形如 `v1.2` 或 `v1.2.3.4` 都会被拒绝（`::error::版本号格式非法`），必须是三段式 SemVer。

**用户装不上新版：`INSTALL_FAILED_UPDATE_INCOMPATIBLE`**
新旧包签名不一致。先在系统里卸载旧版再装新版即可。

**打包很慢**
CI 用了 `gradle/actions/setup-gradle` 缓存依赖，首次构建会下载 Gradle 9.6.1、AGP 9.3.1 与 Android SDK 37，耐心等待。

**`lintVitalRelease` 报 `BlockedPrivateApi`：Reflective access to ... is forbidden when targeting API 37 and above**
本模块的本质就是在 SystemUI 进程内反射 framework / MIUI 私有成员，而 targetSdk 37 起 lint 把这类访问判为致命错误并卡住 release 构建（v1.1.0 首次发版踩到，报错点是 `RingWindowController` 取 `ViewRootImpl.mSurfaceControl`）。已在 `app/build.gradle.kts` 用 `lint { disable += "BlockedPrivateApi" }` 关闭这一项检查并写明理由——隐藏 API 名单按调用方 targetSdk 生效，SystemUI 属平台侧进程不受此限，真机已实证反射可用（可行性分析报告 §17、§19）。**不要**改成 `abortOnError = false`，那会连带放过所有 lint 致命项。

**`Warning: Failed to find package 'tools'`，job 在「准备 Android SDK」挂掉**

`android-actions/setup-android@v3` 的 `packages` 默认值是 `tools platform-tools`，但那个早于 cmdline-tools 的 `tools` 包已被 Google 下架，`sdkmanager tools` 返回 exit code 1 就把 job 打断。工作流里已显式改成 `packages: 'platform-tools'`；Android SDK 37 与 build-tools 交给 AGP 在许可已接受的前提下自动补齐。升级该 action 时留意这个默认值。

**`./gradlew: Permission denied`，exit code 126**
`HolePowerRing/gradlew` 在仓库里丢了可执行位（模式是 `100644`）。Windows 上 `core.fileMode=false`，`chmod +x` 不会进索引，必须显式执行：

```bash
git update-index --chmod=+x HolePowerRing/gradlew
git commit -m "fix(ci): 恢复 gradlew 可执行位"
```

仓库根目录的 `.gitattributes` 已把 `gradlew` 固定为 LF——它靠 `#!/usr/bin/sh` 解释执行，CRLF 会导致同类失败。