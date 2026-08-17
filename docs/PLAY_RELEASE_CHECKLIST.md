# Google Play release checklist / Google Play 发布清单

Complete this checklist for the exact release commit. A green CI run cannot replace the physical-device safety gate or current store-policy review.

每个发布 commit 都必须重新完成本清单。绿色 CI 不能替代实体机安全验证或最新商店政策审查。

## Product and policy / 产品与政策

- [ ] Store name, launcher label and screenshots say **Anchor Watch**; maker identity says **Made aboard Yokuli**.
- [ ] Maker facts match the verified story: developer/former programmer `kuku`; captain `yoyo`; crew `kuku`, `yoyo`, `lili`; refitted Alan Wright-designed Lotus 10.6 `Yokuli`; no invented claims or portraits.
- [ ] Full functionality is free; no billing library, ads, analytics, supporter state or digital benefit exists.
- [ ] Buy Me a Coffee is an optional external link with confirmation and the exact configured URL.
- [ ] Current Google Play payment, donation, external-link, metadata and Data safety rules were reviewed on release day.
- [ ] Owner has chosen a software licence, or release is intentionally blocked while the owner decision remains pending.

## Android and signing / Android 与签名

- [ ] `compileSdk` and `targetSdk` are API 36 or the then-current required level.
- [ ] `versionCode` increases; `versionName` exactly matches the release tag.
- [ ] Stable comes from `main`; beta/alpha branch and tag topology passes the workflow validator.
- [ ] Release keystore is backed up securely and exists only in CI secrets.
- [ ] Google Maps key is restricted to `com.yokuli.anchorwatch` plus every release SHA certificate and enables only Maps SDK for Android.
- [ ] LINZ key/template is a separate restricted secret; no key appears in Git, logs, APK-facing UI or Support Bundles.

## Automated gates / 自动化门禁

- [ ] JVM unit tests pass.
- [ ] Android Lint passes for Release.
- [ ] Android 14 full device story suite passes.
- [ ] Android 16 / API 36 launch and 200% text accessibility smoke passes.
- [ ] Signed APK and AAB build; `apksigner verify` passes; SHA-256 checksums are attached.
- [ ] About/onboarding/crew/YouTube/support link tests pass and operational safety pages contain no marketing.

## Physical release gate / 实体发布门禁

- [ ] [PHYSICAL_SOAK_CHECKLIST.md](PHYSICAL_SOAK_CHECKLIST.md) is completed on the intended phone, power supply and boat NMEA/Wi-Fi.
- [ ] Audible alarm, snooze, acknowledge, pause, range adjustment and lift-anchor clearing are verified with screen on/off.
- [ ] Passive disconnect/reconnect, process death and reboot behavior are observed and truthfully documented.
- [ ] System/NMEA source lock, GPS proxy exit and same-stream sonar GPS/depth pairing are verified.
- [ ] Backup/restore and Support Bundle have been inspected for precise-location and credential leakage.
- [ ] Screenshots and bilingual copy match the shipping APK; no outdated logo or product name remains.

## Listing claims / 商品详情声明

Never claim that the App guarantees safety, replaces official charts/watchkeeping, has certifications, supports a region not actually configured, or has test results from a different commit. State that regional layers and personal sonar are reference aids and that background reliability depends on device/vendor configuration.

不得声称 App 保证安全、替代官方海图/值守、拥有不存在的认证、支持未实际配置的地区，或引用其他 commit 的测试结果。必须明确地区图层与个人声呐仅供参考，后台可靠性还受设备与厂商配置影响。
