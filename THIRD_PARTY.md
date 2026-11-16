# 第三方软件与开源项目清单

本仓库的 GIF 编解码（编码 / 解码 / 中位切分量化 / LZW）全部自行实现，**没有引入任何第三方 GIF 库**
（无 Glide、无 Coil、无 android-gif-drawable、无 gifencoder/gifdecoder 之类的库）。
下面列出实际使用到的运行依赖、测试依赖、构建工具与开发辅助工具。

> 版本号与 `app/build.gradle.kts`、`build.gradle.kts`、`gradle/wrapper/gradle-wrapper.properties` 保持一致。
> AndroidX / Kotlin / Gradle 相关库会带出各自的传递依赖（如 kotlin-stdlib、androidx.annotation、androidx.lifecycle、
> androidx.core-common、androidx.collection 等），它们的许可与直接依赖一致，均由 Gradle 依赖解析自动引入，未手动复制源码。

## 一、运行时依赖（打包进 APK）

| 项目 | 版本 | 许可 | 用途 |
|---|---|---|---|
| [Kotlin Standard Library](https://kotlinlang.org/) | 2.1.21（随 Kotlin 插件） | Apache-2.0 | 语言运行时 |
| [AndroidX Core KTX](https://developer.android.com/jetpack/androidx/releases/core) (`androidx.core:core-ktx`) | 1.13.1 | Apache-2.0 | Context/资源等 Kotlin 扩展 |
| [AndroidX AppCompat](https://developer.android.com/jetpack/androidx/releases/appcompat) (`androidx.appcompat:appcompat`) | 1.7.0 | Apache-2.0 | 兼容主题与 Activity 基类 |
| [Material Components for Android](https://github.com/material-components/material-components-android) (`com.google.android.material:material`) | 1.12.0 | Apache-2.0 | 对话框、按钮、滑块等基础控件外观 |
| [AndroidX Activity KTX](https://developer.android.com/jetpack/androidx/releases/activity) (`androidx.activity:activity-ktx`) | 1.9.3 | Apache-2.0 | `registerForActivityResult`、返回回调 |
| [AndroidX ExifInterface](https://developer.android.com/jetpack/androidx/releases/exifinterface) (`androidx.exifinterface:exifinterface`) | 1.3.7 | Apache-2.0 | 读取照片 EXIF 方向，导入图片自动转正 |

## 二、测试依赖（仅参与测试构建，不进入发布 APK）

| 项目 | 版本 | 许可 | 用途 |
|---|---|---|---|
| [JUnit 4](https://github.com/junit-team/junit4) (`junit:junit`) | 4.13.2 | EPL-1.0 | 11 个 JVM 单元测试（编解码、性能基准） |
| [AndroidX Test Runner](https://developer.android.com/jetpack/androidx/releases/test) (`androidx.test:runner`) | 1.6.2 | Apache-2.0 | 设备内 instrumentation 测试执行器 |
| [AndroidX Test Core](https://developer.android.com/jetpack/androidx/releases/test) (`androidx.test:core`) | 1.6.1 | Apache-2.0 | `ActivityScenario` 启动被测页面 |
| [AndroidX Test Ext JUnit](https://developer.android.com/jetpack/androidx/releases/test) (`androidx.test.ext:junit`) | 1.2.1 | Apache-2.0 | `AndroidJUnit4` 运行器 |

## 三、构建与开发工具（不被打包，仅构建期使用）

| 项目 | 版本 | 许可 | 用途 |
|---|---|---|---|
| [Gradle](https://gradle.org/) | 8.14.3 | Apache-2.0 | 构建系统（wrapper 指向华为云镜像下载） |
| [Android Gradle Plugin](https://developer.android.com/build) | 8.13.2 | Apache-2.0 | Android 构建插件 |
| [Kotlin Gradle Plugin](https://kotlinlang.org/) | 2.1.21 | Apache-2.0 | Kotlin 编译 |
| [Android SDK Platform 36 / Build-Tools 36.1.0](https://developer.android.com/studio) | 36 / 36.1.0 | Android SDK Terms | 编译目标与打包、`apksigner` 签名校验 |
| [Python 3](https://www.python.org/) | 3.x | PSF License | 仅用于 `tools/` 下的辅助脚本 |
| [Pillow (PIL)](https://python-pillow.org/) | 11.x | HPU / PIL Software License | `tools/make_icon.py` 生成应用图标、`tools/make_site_assets.py` 生成官网图片素材 |

## 四、标准与格式

| 名称 | 说明 |
|---|---|
| GIF89a 规范 | 编码输出遵循 GIF89a（含局部调色板、1bit 透明、逐帧延时）；LZW 压缩按规范实现，相关历史专利已全部到期 |
| NETSCAPE2.0 Application Extension | 用于写入循环次数（`loopCount`） |
| Android Photo Picker (`PickVisualMedia`) | 选图/选 GIF 的系统组件，无需存储权限 |

## 五、素材与字体

- 应用图标、模板缩略图、官网配图均由本仓库脚本生成或为本应用真机截图，未使用第三方图库素材。
- 界面未引入第三方字体，中文与 emoji 使用系统字体渲染；因此不同机型上 emoji 字形会随系统字体略有差异。
- 官网 `docs/index.html` 的插图来自 `docs/screens/`（真机截图）与 `docs/posters/`（本应用宣传海报），不使用外部 CDN 资源。

## 六、不属于本项目但被"参考"的内容

- 《GIF编辑器小程序开发需求文档》为用户提供的需求原文，已整理为 `docs/需求文档原文.md`，其中的 P2/P3 功能
  （录屏转 GIF、GIF 转视频、GIF 拆帧、逐帧单独编辑、AI 抠图、云端素材库、分享链接、会员体系）未在本版本实现，
  详见 `docs/技术方案.md` 第九节。
- 未复制任何第三方项目的源码片段；若后续引入，会在此文件与代码注释中同时标注来源与许可。

---

如发现遗漏或版本书写不一致，欢迎提 Issue 指正。
