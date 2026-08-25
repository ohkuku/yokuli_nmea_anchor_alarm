# Boat Watch product identity and compatibility / 产品身份与兼容边界

## Canonical identity / 标准身份

| Field | Canonical value |
|---|---|
| Product name / 产品名 | `Boat Watch` |
| Android application ID | `com.yokuli.anchorwatch` |
| Anchor-alarm feature / 锚警功能名 | `Anchor Watch` / `锚警` |
| Maker line | `Developed aboard SV Yokuli` |
| Developer / 开发者 | `kuku` |
| Captain / 船长 | `yoyo` |
| Crew / 船员 | `kuku`, `yoyo`, `lili` |
| Boat / 船 | `Yokuli` · Alan Wright-designed `Lotus 10.6` · refitted by the crew |
| YouTube | `https://www.youtube.com/@yokuli_ocean_diary` |
| Buy Me a Coffee | `https://buymeacoffee.com/ukus3yya8a` |
| Feedback email / 反馈邮箱 | `kuku.the.developer@gmail.com` |

The supplied `docs/images/anchor-watch-logo.png` is the canonical product logo. Verified maker facts are: kuku previously worked as a programmer and began a sailing life in New Zealand; yoyo is captain; the crew refitted Yokuli, an Alan Wright-designed Lotus 10.6; they plan to explore New Zealand’s islands and bays and may travel farther if circumstances allow. Do not generate crew portraits or invent additional roles, qualifications, biographies, reviews, awards, user counts or safety claims.

用户提供的 `docs/images/anchor-watch-logo.png` 是正式产品标志。已经确认的创作者信息为：kuku 曾是一名程序员，并在新西兰开始航海生活；yoyo 是船长；团队翻新了由 Alan Wright 设计的 Lotus 10.6——Yokuli；计划先探索新西兰的岛屿与海湾，有机会再去往更远的世界。不得生成船员人像或虚构其他职业、资历、传记、评价、奖项、用户数量或安全声明。

## Product and maker roles / 产品与创作者的边界

Boat Watch is calm, practical, marine and trustworthy. Yokuli’s story is human, curious, still learning and exploring. Maker content belongs in first-run onboarding and Settings; the root Settings page may expose the voluntary-support entry while About & support carries the full story. Watch, alarm, Data and History remain operational and contain no donation, YouTube, rating or promotional prompt.

Boat Watch 的产品语气应当平静、实用、海洋化、可信。Yokuli 的故事应当自然、真诚，并诚实表达仍在学习和探索。创作者内容放在首次介绍与设置区；设置首页可以提供自愿支持入口，“关于与支持”承载完整故事。锚警、报警、数据和历史页面不得出现捐助、YouTube、评分或推广提示。

## Rename compatibility / 改名兼容边界

The launcher label, About/onboarding copy, feedback body, branded exports, share cards, store copy, workflow titles and newly published artifact labels use **Boat Watch**. **Anchor Watch** remains the name of the anchor-alarm feature inside the App.

A visible rename must not create a second Android App or invalidate installed data. These legacy technical identifiers therefore remain unchanged until a separately planned migration proves otherwise:

- application ID and Kotlin package `com.yokuli.anchorwatch`;
- App class/theme/internal source names such as `AnchorWatchApp`;
- Room database/schema identity and backup archive format identifiers;
- the existing `anchorwatch://` V1/V2 anchorage QR/deep-link scheme;
- FileProvider authority, notification channel IDs and DataStore keys;
- release certificate, keystore alias and historical keystore filenames;
- repository URL and historical document/asset filenames.

可见名称变化不能让 Android 把升级版当成另一个 App，也不能让旧数据失效。因此包名、数据库、备份格式、旧 `anchorwatch://` 二维码协议、通知 channel、签名证书/alias 等技术标识继续保留。新生成的文件和可见文案使用 Boat Watch，但仍须读取旧版备份与二维码。

## Configuration / 构建配置

Links are provided through BuildConfig fields and may be deliberately overridden with an empty value:

- `YOKULI_YOUTUBE_URL`
- `YOKULI_BUYMEACOFFEE_URL`
- `YOKULI_WEBSITE_URL`
- `YOKULI_CONTACT_EMAIL`
- `YOKULI_PRIVACY_URL`
- `YOKULI_SOURCE_CODE_URL`

An absent optional URL must remove its action instead of showing a broken button. External links allow only `https` or a valid `mailto` address and open outside the App.

可选 URL 为空时必须隐藏对应操作，不能显示坏按钮。外链只允许 `https` 或有效的 `mailto`，并在 App 外打开。

## Licence / 软件许可

**OWNER MUST CHOOSE SOFTWARE LICENCE.** No open-source or proprietary licence may be inferred or added automatically. Until the owner decides, the repository and About page must say that no licence is implied.

**软件许可必须由所有者决定。** 不得自动推断或添加开源/专有许可。在所有者决定前，仓库与 About 页面必须明确“不默示授予许可”。
