# Release signing / 正式版签名管理

## 中文

Anchor Watch 的 GitHub 直发 APK 必须永远使用同一把签名密钥。仓库内的
`scripts/signing/manage-signing.sh` 管理本机签名，但实际密钥位于被 Git 忽略的
`.signing/`，两个密码保存在 macOS Keychain，不写入源码或普通配置文件。

### 第一次初始化

```bash
scripts/signing/manage-signing.sh init
```

脚本会创建 `.signing/anchor-watch-release.jks`，要求输入并确认两个至少 12 位的密码，
并拒绝覆盖任何现有密钥。初始化后立即检查并备份：

```bash
scripts/signing/manage-signing.sh status
scripts/signing/manage-signing.sh fingerprint
scripts/signing/manage-signing.sh backup /明确指定的加密备份目录
```

至少保留两份离线或加密备份，并把 Keystore password 与 Key password 记录在密码管理器。
macOS Keychain 只是本机日常使用副本，不应是唯一的密码备份。

### 配置 GitHub Actions Secrets

没有 GitHub CLI 时，每次复制一个值，然后粘贴到
`GitHub → Settings → Secrets and variables → Actions`：

```bash
scripts/signing/manage-signing.sh copy-secret ANDROID_SIGNING_KEY_BASE64
scripts/signing/manage-signing.sh copy-secret ANDROID_KEYSTORE_PASSWORD
scripts/signing/manage-signing.sh copy-secret ANDROID_KEY_ALIAS
scripts/signing/manage-signing.sh copy-secret ANDROID_KEY_PASSWORD
```

安装并登录 `gh` 后可一次上传：

```bash
scripts/signing/manage-signing.sh github-secrets
```

GitHub 只允许读取 Secret 名称，不允许重新显示值。因此本机密钥和安全备份仍然是权威来源。

### Google Maps 正式证书

运行 `fingerprint`，把正式证书 SHA-1 与包名 `com.yokuli.anchorwatch` 一起加入 Google Maps
API Key 的 Android 应用限制。Debug SHA-1 与 Release SHA-1 是两个不同条目。

### 本地构建正式版

```bash
scripts/signing/manage-signing.sh build-release 1.0.0 1
```

这只用于特殊情况下的本机诊断构建，不创建 tag，也不上传 GitHub 或 Google Play。正常发布
使用以下命令；它只推送 tag，签名、测试、构建和 GitHub Release 全部在线执行：

```bash
scripts/release/manage-release.sh status
scripts/release/manage-release.sh publish v1.1.0-alpha.1
```

也可以在 GitHub 的 `Publish Anchor Watch Release` 手动 Action 中选择正确分支并只填写 tag。

### 不可逆规则

- 不提交 `.signing/`、`.jks` 或密码。
- 不为普通更新创建新密钥。
- 不删除旧密钥或正式 tag。
- 丢失/替换密钥后，已安装的 GitHub APK 无法覆盖升级。
- 脚本刻意不提供 delete、reset 或 rotate 命令。

## English

Direct-distribution Anchor Watch APKs must always use the same signing key. The repository
manager stores the keystore under the Git-ignored `.signing/` directory and stores the two
passwords in macOS Keychain.

Initialize once:

```bash
scripts/signing/manage-signing.sh init
```

Inspect and back up immediately:

```bash
scripts/signing/manage-signing.sh status
scripts/signing/manage-signing.sh fingerprint
scripts/signing/manage-signing.sh backup /an/explicit/encrypted/backup/directory
```

Configure the four GitHub Actions Secrets using `copy-secret`, or use `github-secrets` after
installing and authenticating GitHub CLI. Keep at least two encrypted/offline keystore copies
and record both passwords in a password manager. macOS Keychain is a convenience copy, not
the only backup.

Build locally without publishing:

```bash
scripts/signing/manage-signing.sh build-release 1.0.0 1
```

Normal releases are built online. From a clean branch that exactly matches its remote, push a validated
release tag with:

```bash
scripts/release/manage-release.sh publish v1.1.0-alpha.1
```

GitHub Actions performs the safety gates, signing, APK/AAB build, verification, and GitHub Release
publication. The manual workflow remains available as a tag-only fallback.

The tool deliberately has no delete, reset or rotate operation. Replacing the key prevents
installed direct-distribution APKs from receiving future updates.
