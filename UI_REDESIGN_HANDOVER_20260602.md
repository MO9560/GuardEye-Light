# GuardEye UI 重设计 — 工作交接文档

> 日期：2026-06-02
> 范围：`android-guard-bot` 项目 UI 改版（浅蓝配色，卡片式布局）

---

## 一、改动概述

将原本扁平堆叠的布局改为 **卡片式（Card-style）** 设计，采用 **浅蓝配色**，并强制 App 使用浅色主题（不受系统深色模式影响）。

---

## 二、修改文件清单

### 布局文件
| 文件 | 改动内容 |
|------|----------|
| `app/src/full/res/layout/activity_main.xml` | 全面重写。用 `MaterialCardView` 将界面分为 4 张卡片：状态卡 / 配置卡 / 设置卡 / 调试卡 |

### 资源文件
| 文件 | 改动内容 |
|------|----------|
| `app/src/main/res/values/colors.xml` | 重写配色：主色 `#2196F3`，背景 `#F0F8FF`，卡片白底，描边 `#C8E6F8` |
| `app/src/main/res/values/styles.xml` | 添加 `android:forceDarkAllowed=false`，强制浅色主题；状态栏改用 `primary` 蓝色 |
| `app/src/main/res/drawable/bg_gradient.xml` | 渐变改为浅蓝（`#E3F2FD` → `#F0F8FF`） |
| `app/src/main/res/drawable/card_bg.xml` | 圆角改为 `14dp`，描边颜色同步更新 |
| `app/src/main/res/drawable/edit_bg.xml` | 输入框背景改为浅蓝底 `#F0F8FF` |
| `app/src/main/res/drawable/badge_bg.xml` | 版本号徽章背景改为浅蓝 `#E3F2FD` |
| `app/src/main/res/drawable/btn_primary.xml` | 标题图标背景色更新为 `primary` 蓝色 |
| `app/src/main/res/drawable/ic_arrow_up.xml` | 箭头填充色修正为 `#FF2196F3`（原 `#FFFFFFFF` 有误） |

---

## 三、新布局结构说明

```
ScrollView
├── Header（App 图标 + GuardEye 标题 + 版本徽章）
├── 状态卡片（MaterialCardView）
│   ├── Bot 状态（绿/琥珀色圆点 + 文字）
│   └── 下次拍照时间
├── 配置卡片（MaterialCardView）
│   ├── Bot Token 输入框（TextInputLayout 包裹）
│   ├── Chat ID 输入框（TextInputLayout 包裹）
│   └── 拍照间隔拖拽条（SeekBar）
├── 设置卡片（MaterialCardView）
│   ├── AI Detection 开关
│   └── Debug Mode 开关
├── 操作按钮（Save / Start）
└── 调试面板卡片（可折叠）
```

---

## 四、已知问题 / 待确认

1. **Gradle 构建未验证**：因沙盒权限问题，本次未能在本地完成 `./gradlew assembleFullDebug` 构建验证，需要接手同事手动 build 确认。
2. **`ic_arrow_up.xml` 兼容性**：`#FF2196F3` 写法（AARRGGBB）在部分 Android 版本需注意，建议验证箭头显示是否正常。
3. **`full` flavor 专属**：`activity_main.xml` 只改了 `src/full/res/layout/`，`light` flavor 的布局未同步更新，如需要请一并修改 `app/src/light/res/layout/`（如有）。

---

## 五、配色参考

| 用途 | 色值 | 说明 |
|------|-------|------|
| 主色 / 强调色 | `#2196F3` | 按钮、SeekBar、链接 |
| 强调色（Start按钮） | `#039BE5` | 主操作按钮背景 |
| 背景（起始渐变） | `#E3F2FD` | 页面顶部背景 |
| 背景（结束渐变） | `#F0F8FF` | 页面底部背景 |
| 卡片背景 | `#FFFFFF` | 所有 MaterialCardView |
| 卡片描边 | `#C8E6F8` | 卡片边框 |
| 状态绿 | `#22C55E` | Bot 正常运行指示 |
| 状态琥珀 | `#F59E0B` | Bot 未配置 / 警告 |
| 文字主色 | `#1A1A2E` | 标题、正文 |
| 文字次色 | `#6B7280` | 标签、说明文字 |

---

## 六、下一步建议

- [ ] 在 Android Studio 或命令行完成一次完整 build，确认无编译错误
- [ ] 在真机上运行，验证浅色主题是否生效（开启系统深色模式测试）
- [ ] 验证 `ic_arrow_up` 箭头图标显示正常
- [ ] 如 `light` flavor 也需要新 UI，同步修改对应布局文件
- [ ] 如视觉效果需要调整，优先修改 `colors.xml` 统一调整，避免散改各文件

---

*文档生成时间：2026-06-02 21:05*
