# GuardEye Light

远程相机 Android 应用（Light 版）

## 功能

- 📛 **告票查询**：查询车辆违章记录，推送到 Telegram
- 📷 **远程拍照**：通过 Telegram 指令远程触发手机拍照
- 🔋 **后台保活**：五层保活策略，确保服务持续运行
- 📱 **TG Bot 控制**：12 个 Telegram 指令，完全远程控制

## Telegram 指令

| 指令 | 功能 |
|------|------|
| `/start` | 启动监控 |
| `/stop` | 停止监控 |
| `/photo` | 拍照（默认后镜头） |
| `/photo f` | 拍照（前镜头） |
| `/ticket` | 查询告票 |
| `/ticket MO1360,AA1186` | 查询指定车牌告票 |
| `/interval 10` | 设置监控间隔（分钟） |
| `/status` | 查看状态 |
| `/test` | 测试连接 |
| `/battery` | 电池优化提示 |
| `/debug` | 切换调试模式 |

## 安装

1. 下载最新 Release APK
2. 安装到 Android 手机（minSdk 26 / Android 8.0）
3. 打开应用，授予权限（相机、存储、后台运行）
4. 在设置页配置 Telegram Bot Token 和 Chat ID
5. 发送 `/start` 启动监控

## Telegram Bot 配置

1. 找 @BotFather 创建 Bot，获取 Token
2. 打开 https://api.telegram.org/bot<TOKEN>/getUpdates 获取 Chat ID
3. 在应用设置页填入 Token 和 Chat ID

## 技术栈

- **语言**：Kotlin
- **相机**：CameraX
- **网络**：OkHttp
- **TG Bot**：Telegram Bot API（长轮询）
- **告票查询**：FSM 网站爬取（澳门交通违例查询系统）

## 版本

当前版本：`v2.3.9`

查看完整变更记录：[CHANGELOG.md](CHANGELOG.md)

## License

MIT
