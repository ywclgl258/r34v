# 2.9.1 界面密度与 4KHD 修复验收

日期：2026-11-22；正式包名 `com.r34v.r34v`，版本 `2.9.1 / 51`。

## 本轮范围

用户反馈四项界面问题与一个站点故障（过程记录见 [roadmap-2026-11-21](roadmap-2026-11-21.md) 第六、七轮）：

1. **4KHD 恢复**（缺陷），见下。
2. **全局密度**：手机（字体 ≤1.3x）默认走紧凑档——标题 26→19sp、正文 16→13sp、按钮高度 48→38dp，控件内边距与页边距同步收紧；大字体（>1.3x）仍回宽松档。`Title`/`Copy`/`Glyph`/`Action`/`Choice`/`Selector` 逐项下调一档。
3. **写真站瀑布流**：`MediaResumeHero` 手机分支由满屏 Hero（`heightIn(min=340dp)` + 全宽主按钮）改为紧凑横排卡（88dp 缩略图 + 两行标题 + 主按钮）。
4. **详情页按钮**：图集详情 7 个纵向按钮（多个全宽）→ 一行三按钮（收藏/稍后看/下载）+“更多”菜单（缩略图总览、导出、合集、管理下载、选页）；视频详情三行大按钮 → 一行工具条（档位/倍速/收藏/下载/更多）。
5. **跨站搜索重做**：每站一个分组标题 + 固定行布局 → 单一混合瀑布流（列数随宽度 2–6）+ 来源筛选 chips（带每站计数）+ 失败来源单独列出 + 每站独立“加载更多”。

### 4KHD 缺陷根因

`SiteHtml.challenge()` 的判定 `challenge-platform && length < 120_000 && !</video>` 命中误判。实测主机侧直连首页 HTTP 200 / 102KB / 标题正常，**不是 Cloudflare 拦截**：4KHD 正常内容页里注入的是 Cloudflare 被动 JS 检测（`/cdn-cgi/challenge-platform/scripts/jsd/main.js`），页面 102KB 小于阈值，于是每个页面都被判成挑战页 → 走镜像链（4KHD 无镜像）→ 报“站点要求浏览器验证”。另外核对了站点自定义的 `/scss.js` 与 `/worker.js`：混淆脚本做的是 service worker 缓存清理，不含准入校验。

修复：判定必须有明确拦截证据才拒——标题级提示（`Just a moment` / `Checking your browser`）、`cf-mitigated`、`DDoS-Guard` 且无列表结构，或 `challenge-platform` **且页面无任何内容容器**（`</video`、`.thumbs`、`article`、`entry-title`、`wp-content`、`<main`、`video-card`）。

## 实现与本地验证

- 305 项 JVM 通过，0 失败。新增 `SiteHtmlTest.passiveCloudflareInjectionOnARealPageIsNotAChallenge`（正常内容页不拦 / 真拦截页拦 / 空壳仍拦）。
- Debug / Release Lint 均 0 errors / 28 warnings，与 2.9.0 基线一致。
- 模拟器 r34v_api24 实测：
  - **4KHD 首页**：Hero 与瀑布流封面正常加载（修复前报“站点要求浏览器验证”）。
  - **写真站（Cosplaytele）首页**：瀑布流首屏可见 4 个封面（修复前一屏看不到任何封面）。
  - **写真集详情页**：动作区由满屏 7 个纵向按钮缩为一行三按钮 + 更多，整页由满屏缩到半屏。
  - **跨站搜索**：同一关键词返回 12 个来源 219 项，混合瀑布流交错排列，来源筛选 chips 显示每站计数，失败来源单独列出。
- 未验证边界：大字体（>1.3x）与平板宽屏下的新密度未复核；视频详情页工具条在 API 24 上未逐个点击验证；4KHD 详情页与阅读器未深入验证（仅首页）。

## 正式交付

- 使用仓库 Wrapper 执行 `:app:validateReleaseSigning :app:assembleRelease`（`R34V_SIGNING_HOME` 固定目录），既有 `signUpgradeRelease` 完成，`app/signing/upgrade.lineage` 未改动。
- 包名与版本经 `aapt2 dump badging` 核对：`com.r34v.r34v`，`2.9.1 / 51`（标准包与兼容包一致）。
- 标准包 API 24–27 验签通过，历史证书 SHA-256：`73543870ea2dd9ead7888b743a3b6b4ccecc82a3e361216a7ded714ca3cdbc4e`。
- 标准包 API 28+、兼容包 API 24–27 验签通过，当前证书 SHA-256：`75f3270c0914286315881f8f1a3d7b9a0c7e26d9f6a2e86354d91f924907c5e7`。
- 标准 APK 文件 SHA-256：`1c278f10ab9391f87adc107a5f38de62a608c5bec62dfe062c3cd330e26d3a46`；兼容 APK 文件 SHA-256：`ff51eb24ea3a33aa271831925821856f42c62039f223876fab43165ce165ddc9`。文件摘要不等同证书指纹。
- 兼容包仅用于 Android 7/8 上已安装迁移版 2.0.0 的用户；一般用户选择标准包。
