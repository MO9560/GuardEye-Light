# VisoMaster Fusion 移动硬盘便携部署方案

> 适用场景：将 VisoMaster Fusion 安装在移动硬盘，在多台 Windows 机器之间插拔使用
> 生成时间：2026-06-20

---

## 一、架构说明

```
┌─────────────────────────────────────────────┐
│  移动硬盘（H:/ 或 D:/ 等盘符）               │
│                                             │
│  H:/VisoMaster/                             │
│  ├── Start_Portable.bat    ← 唯一启动入口    │
│  ├── portable-files/       ← 便携运行时      │
│  │     ├── python/         ← Python 3.12    │
│  │     ├── uv/             ← 包管理器        │
│  │     ├── git/            ← Git CLI        │
│  │     ├── ffmpeg/         ← FFmpeg 7.1     │
│  │     ├── models/         ← AI 模型文件     │
│  │     └── uv-cache/       ← pip 缓存       │
│  │                                     ↕     │
│  ├── VisoMaster-Fusion/  ← 源码（可选）      │
│  ├── _PROJECTS/          ← 待处理素材        │
│  ├── _OUTPUTS/            ← 成品输出         │
│  └── docs/               ← 用户手册备份      │
└─────────────────────────────────────────────┘
              ↑ 插到哪台机器，哪台机器就能跑
              但 GPU 必须在该机器上（本地安装）
```

**关键约束：** 移动硬盘只放应用程序和文件素材。**CUDA/TensorRT 需在各机器本地安装一次**，Launcher 会自动处理，首次运行后各机器可独立使用。

---

## 二、前置要求

### 目标机器（每台要用的电脑）
| 项目 | 最低 | 推荐 |
|---|---|---|
| 系统 | Windows 10 x64 | Windows 11 x64 |
| 显卡 | Nvidia GTX 1060 6GB | RTX 3080+ / RTX 4070+ |
| 显存 | 6GB | 8GB+ |
| 内存 | 16GB | 32GB |
| 硬盘（机器本地） | 30GB 空闲 | 50GB+ |
| 驱动 | Nvidia Driver >= 526 | 最新 Studio 驱动 |

### 移动硬盘本身
| 项目 | 要求 |
|---|---|
| 接口 | USB 3.0+（推荐 USB 3.2 / USB-C） |
| 容量 | 128GB+（模型包 20-30GB，建议 256GB 以上） |
| 格式 | NTFS（支持 >4GB 单文件） |
| 读写速度 | 连续读取 >100MB/s |

---

## 三、部署步骤

### Step 1 — 移动硬盘创建目录结构

在移动硬盘根目录新建文件夹：

```
H:/
 └── VisoMaster/
      ├── _PROJECTS/      ← 素材放这里
      ├── _OUTPUTS/       ← 成品输出在这里
      └── docs/           ← 手动下载 user_manual.md 放此备份
```

### Step 2 — 下载启动器

在电脑浏览器打开：
```
https://github.com/VisoMasterFusion/VisoMaster-Fusion/releases/latest
```
下载 `Start_Portable.bat`，放到 `H:/VisoMaster/` 目录下。

**重要：** 只下载这一个 .bat 文件，不要下载其他东西。

### Step 3 — 首次在每台机器上运行（一次性）

> 这一步在各机器上只需执行一次，之后插上移动硬盘直接运行即可。

1. 把移动硬盘插上电脑
2. 确保电脑已安装 **Nvidia 显卡驱动**（官网下载安装）
3. 双击运行 `H:/VisoMaster/Start_Portable.bat`
4. 选 **"Full Setup"**（完整安装）
5. 等待自动下载（约需 20-40 分钟，看网速）：
   - Python 3.12 embeddable（约 25MB）
   - uv 包管理器（约 5MB）
   - Git（约 50MB）
   - FFmpeg（约 80MB）
   - CUDA Toolkit（约 2-3GB）
   - TensorRT（约 1-2GB）
   - AI 模型包（约 5-10GB，视所选模型而定）

下载过程中**不要关闭窗口**，网络中断可重新运行，Launcher 会跳过已下载的部分。

### Step 4 — 验证安装

安装完成后，Launcher 会自动启动 VisoMaster Fusion GUI。

关闭 GUI，在移动硬盘目录确认以下文件夹已生成：
```
H:/VisoMaster/
 ├── portable-files/       ← 运行时（含 python、ffmpeg、git）
 ├── VisoMaster-Fusion/    ← 源码
```

---

## 四、日常使用流程

### 在任意机器上启动

```
1. 电脑接上移动硬盘
2. 双击 H:/VisoMaster/Start_Portable.bat
3. 等待 10-20 秒（自动检测已安装组件）
4. VisoMaster Fusion GUI 启动
```

### 素材管理（推荐工作流）

```
素材输入：   复制到  H:/VisoMaster/_PROJECTS/
成品输出：   自动保存在 H:/VisoMaster/_OUTPUTS/
                    └── [日期]_[项目名]/
```

> 每次开工前建议在 App 内把 Output 路径改成 `H:/VisoMaster/_OUTPUTS/[当天日期]`，方便区分项目。

### 多机器切换

在不同电脑之间切换时：
- 模型文件在移动硬盘（通用）
- **TensorRT Engine 文件** 在 `portable-files/tensorrt-engines/`，不同显卡架构不通用
- 如果换到不同显卡，重新运行 `Start_Portable.bat` → 选 **Repair / Re-download** 重新生成 Engine

---

## 五、存储空间规划

| 目录 | 预计大小 | 说明 |
|---|---|---|
| `portable-files/` | ~30GB | 运行时 + CUDA + 模型 |
| `VisoMaster-Fusion/` | ~1GB | 源码 |
| `_PROJECTS/` | 动态 | 原始素材（视频、图片） |
| `_OUTPUTS/` | 动态 | 处理成品（通常比原片大） |
| **合计（不含素材）** | **~31GB** | |

建议移动硬盘容量：**256GB 以上**（方便存多个项目的素材和输出）。

---

## 六、模型管理优化

VisoMaster Fusion 首次运行会下载**所有模型**（~10GB）。如果只用到其中一两个，可以删减：

**模型目录位置（安装后）：**
```
H:/VisoMaster/portable-files/models/
```

| 模型 | 大小 | 用途 | 推荐 |
|---|---|---|---|
| inswapper_128 | ~500MB | 通用换脸 | ✅ 保留 |
| inswapper_128_onnx | ~500MB | ONNX 版本 | 可删（已有 FP16） |
| GFPGAN | ~350MB | 人脸修复 | ✅ 保留 |
| RealESRGAN | ~65MB | 图像增强 | ✅ 保留 |
| SimSwap | ~500MB | 高质量换脸 | 按需 |
| GhostFace | ~500MB | 侧脸换脸 | 按需 |
| InStyleSwapper | ~500MB | 风格化换脸 | 按需 |

> 删减模型后，下次用到会提示下载，可以先删减少用的。

---

## 七、多机器差异化处理

### 机器 A：RTX 3080（10GB）
- TensorRT Engine 在本机生成，存 `portable-files/tensorrt-engines/rtx3080/`

### 机器 B：RTX 4090（24GB）
- 重新生成 Engine，或保留两套 Engine，App 会自动识别

### 机器 C：RTX 4060 Laptop（8GB）
- 6GB 显存的卡可以用，但某些高分辨率任务会爆显存

### 切换时注意
- **不要同时插两个移动硬盘**（盘符可能变化）
- 建议给每台机器的 `portable-files/tensorrt-engines/` 下的子目录改名区分

---

## 八、故障排查

| 问题 | 原因 | 解决 |
|---|---|---|
| 启动报错 "CUDA not found" | 本地 CUDA 没装好 | 重新运行 Start_Portable.bat → Repair |
| 提示 "Model missing" | 模型被删或路径错 | 重新运行下载，或手动从 [模型地址] 下载放回 |
| 换到新机器后报错 | TensorRT Engine 不兼容 | 删 `portable-files/tensorrt-engines/`，重新运行生成 |
| 导出视频时长为 0 | FFmpeg 路径问题 | 确保 `portable-files/ffmpeg-*/bin` 在 PATH 中 |
| 显存不足（CUDA OOM） | 显存太小或分辨率太高 | 降低输出分辨率，或关闭 face restorer |
| 移动硬盘盘符变了 | 插到不同 USB 口 | 右键 `此电脑` → `更改盘符`，固定为 H: 或其他 |

---

## 九、启动脚本备份（万一 bat 丢失）

如果 `Start_Portable.bat` 丢失或损坏，从以下地址重新下载：
```
https://github.com/VisoMasterFusion/VisoMaster-Fusion/releases/latest/download/Start_Portable.bat
```

源码仓库（需梯子）：
```
https://github.com/VisoMasterFusion/VisoMaster-Fusion
```

文档备份（手动下载到 docs/）：
```
https://github.com/VisoMasterFusion/VisoMaster-Fusion/blob/main/docs/user_manual.md
https://github.com/VisoMasterFusion/VisoMaster-Fusion/blob/main/docs/quickstart.md
```

---

## 十、推荐工作流摘要

```
[新项目]
1. 插上移动硬盘 → 运行 Start_Portable.bat
2. 素材复制到 _PROJECTS/[项目名]/
3. App 内设置 Input/Output 路径
4. 选择 Source Face → Target Video
5. 调整参数 → Preview → Render
6. 成品自动保存到 _OUTPUTS/[日期]_[项目名]/
7. 完成后拔硬盘，素材留在硬盘，项目完成

[下次继续]
1. 插上硬盘 → 运行 Start_Portable.bat
2. App 内 Open Job（Job Manager）加载上次进度
```
