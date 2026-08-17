# Release Console / 可视化版本发布台

## 中文

Release Console 是 Anchor Watch 仓库自带的本地网页工具。它只监听 `127.0.0.1`，不对局域网
或互联网开放，也不需要在浏览器中填写 GitHub Token、签名密码或 API Key。

从仓库目录运行：

```bash
scripts/release/manage-release.sh console
```

默认浏览器会自动打开。页面把发布过程缩减为四步：

1. 自动识别当前 branch、commit、未提交改动和 GitHub 同步情况。
2. 只显示当前分支允许的 channel：develop 只能 alpha，release 分支可以 alpha/beta，main 可以 beta/stable。
3. 根据已有 tag 推荐下一个 tag，用户可以在发布前修改。
4. 点击一次发布；工具再次拉取远程分支并核对 commit，然后创建并推送不可变 tag。

tag 推送后，本机工作结束。GitHub Actions 在线执行签名预检、API 36 启动 smoke、Unit、Lint、
APK/AAB 构建、签名验证和 SHA-256，全部通过后创建 GitHub Release 下载页。完整 Android
设备 story 在独立集成 workflow 中运行并控制 `debug-verified`，不会阻塞签名发布。

### 页面显示 Not ready 时

- `Local changes` 不干净：先 commit。
- `GitHub sync` 不同步：先 push，或先处理远端更新。
- Channel 全部禁用：切换到 `codex/develop`、`codex/release/*` 或 `main`。
- 签名 Secret 缺失：网页无法也不应该读取 GitHub Secret；在线 workflow 会在签名前明确列出缺失名称。

按 `Control-C` 关闭本地网页。脚本不会删除、移动或 force-push 已有 tag。

## English

Release Console is a repository-local browser interface that listens only on `127.0.0.1`. It never
asks for a GitHub token, signing password, or API key.

```bash
scripts/release/manage-release.sh console
```

The console shows branch and sync readiness, exposes only the channels permitted by the branch,
suggests the next version tag, and publishes it with one confirmed action. Before pushing, the backend
re-fetches the selected branch and requires local `HEAD` to match the remote exactly.

After the immutable tag is pushed, GitHub Actions performs signing preflight, the API 36 launch smoke,
JVM tests, Release Lint, signing, APK/AAB verification, and the GitHub Release upload online. Full
Android device stories run on the separate integration workflow and gate `debug-verified` artifacts
without blocking signed releases. Stop the local console with `Control-C`. It never deletes, moves,
or force-pushes a release tag.
