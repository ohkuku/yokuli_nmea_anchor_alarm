# GitHub Actions secrets / GitHub Actions 密钥配置

This repository keeps API credentials and the Android release key out of Git. Local development reads API values from `local.properties`; GitHub Actions reads matching values from repository-level Actions secrets.

本仓库不会把 API 凭据或 Android 正式签名提交到 Git。本机从 `local.properties` 读取 API 值，GitHub Actions 则从仓库级 Actions Secrets 读取相同的值。

## Exact inventory / 完整清单

| Secret | Needed for | Local source | Requirement |
|---|---|---|---|
| `MAPS_API_KEY` | Google Map and Satellite in Debug, soak and Release builds | `local.properties` | Required for the complete product |
| `LINZ_API_KEY` | LINZ chart tiles, vector depth and tide prediction | `local.properties` | Required for the complete New Zealand feature set |
| `LINZ_HYDRO_TILE_TEMPLATE` | Replace the built-in official LINZ chart-set templates | `local.properties`, only if deliberately added | Optional; leave absent when no local override exists |
| `ANDROID_SIGNING_KEY_BASE64` | Signed Release APK/AAB | `.signing/anchor-watch-release.jks` | Required for Release |
| `ANDROID_KEYSTORE_PASSWORD` | Open the release keystore | macOS Keychain | Required for Release |
| `ANDROID_KEY_ALIAS` | Select the permanent signing key | `.signing/config` | Required for Release |
| `ANDROID_KEY_PASSWORD` | Use the private signing key | macOS Keychain | Required for Release |

The current local `local.properties` contains configured `MAPS_API_KEY` and `LINZ_API_KEY` values, but no `LINZ_HYDRO_TILE_TEMPLATE`. The correct online match is to add or replace the first two and leave the template unset. With a LINZ key, the build already constructs the official LINZ hydrographic chart-set URLs.

当前本机 `local.properties` 已配置 `MAPS_API_KEY` 和 `LINZ_API_KEY`，但没有 `LINZ_HYDRO_TILE_TEMPLATE`。因此线上应添加或覆盖前两项，模板保持不设置。只要有 LINZ key，构建脚本会自动生成官方 LINZ 水文海图集地址。

These are not repository secrets:

- `sdk.dir` is a local Android SDK path; Actions installs its own SDK.
- `VERSION_NAME` and `VERSION_CODE` are derived by the release workflow.
- `GITHUB_TOKEN` is supplied automatically by GitHub.
- YouTube, Buy Me a Coffee, source-code and feedback URLs already have product defaults.
- The current build has no Firebase key, Google Services JSON, Play service-account key or Play upload credential.

以上内容都不需要建立 Secret。

## Configure through GitHub / 用 GitHub 网页配置

Open <https://github.com/ohkuku/yokuli_nmea_anchor_alarm/settings/secrets/actions>. For each value, choose **New repository secret**, enter the exact case-sensitive name, paste the value, and save it. Saving the same name replaces the old value; GitHub never displays a saved value again.

逐项点击 **New repository secret**，输入完全一致且区分大小写的名称，粘贴内容并保存。使用相同名称重新保存会覆盖旧值；GitHub 保存后不会再次显示 Secret 原文。

Use the helper so values go directly from `local.properties` to the macOS clipboard without appearing in terminal output:

```bash
scripts/ci/manage-build-secrets.sh status
scripts/ci/manage-build-secrets.sh copy-secret MAPS_API_KEY
scripts/ci/manage-build-secrets.sh copy-secret LINZ_API_KEY
scripts/ci/manage-build-secrets.sh clear-clipboard
```

For the four signing values, use the permanent signing vault. All four must come from the same vault; never create a new keystore merely to repair CI.

正式签名四项必须全部来自同一个永久签名库；修 CI 时绝对不要重新生成 keystore。

```bash
scripts/signing/manage-signing.sh status
scripts/signing/manage-signing.sh copy-secret ANDROID_SIGNING_KEY_BASE64
scripts/signing/manage-signing.sh copy-secret ANDROID_KEYSTORE_PASSWORD
scripts/signing/manage-signing.sh copy-secret ANDROID_KEY_ALIAS
scripts/signing/manage-signing.sh copy-secret ANDROID_KEY_PASSWORD
```

After each `copy-secret`, paste into the matching GitHub field before copying the next value, then clear the clipboard.

## Optional GitHub CLI path / 可选 GitHub CLI 方式

The website path needs no `gh`. If it is installed and authenticated later, these commands upload the same values without printing them:

```bash
scripts/ci/manage-build-secrets.sh github-secrets
scripts/signing/manage-signing.sh github-secrets
```

## Verification and safety / 验证与安全

The Secrets page should list the two product keys and four signing names. The optional template should remain absent unless a real override is intentionally added. Release runs perform an early signing preflight; Debug and Release receive the same map/LINZ configuration.

Do not paste keys into issues, Actions logs, commit messages, screenshots or support bundles. Restrict the Google Maps key in Google Cloud to this Android application ID and the signing certificate(s) used by the corresponding build.

不要把 key 放进 issue、Actions 日志、commit message、截图或诊断包。Google Maps key 应在 Google Cloud 中限制到本 App 的 application ID 和对应签名证书。
