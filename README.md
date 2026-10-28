# NearCast

Android ↔ Android 局域网屏幕共享与远程控制。单 APK，轻量、无需账号或云服务器。

> **状态：v0.1 实验性原型。仅适合自己控制的设备和可信 Wi-Fi。尚未实现传输加密、跨机型性能验证或发布级稳定性。**

## 已实现（初始原型）

- Kotlin + Jetpack Compose / Material 3 简洁双角色 UI
- Android NSD / mDNS 自动发现与广播（\`_nearcast._tcp.\`）
- 设备列表、一键选取、6 位一次会话配对码；手动 IP:端口备选
- Android MediaProjection → VirtualDisplay → MediaCodec Surface 硬件 H.264 编码
- TCP 测试视频链路 → MediaCodec 硬件解码 → SurfaceView 直显
- 无障碍服务远程点击、滑动（抬手后执行）、返回、Home、最近任务
- 60/90/120 FPS 目标参数（实际刷新率受设备采集/编码能力影响，尚未实测）
- **省电暗屏**：经用户授权，最小化系统屏幕亮度，保持被控设备显示活动；退出共享时还原亮度设置，并在下次启动时进行异常恢复
- MediaProjection 前台服务通知和主动停止入口
- GitHub Actions Debug APK 构建流程

## 重要边界

1. **不能保证物理关屏远控**。Android 15 QPR1 及以后，锁定手机通常会自动终止 MediaProjection。普通无 Root APK 的“省电暗屏”仅把亮度降至系统最低并阻止自动休眠，**不是真正关闭 OLED/屏幕面板**。部分 OEM 可能限制旧式 wake lock 或亮度写入；需实机验证。未来可考虑单独提供 ADB/Shizuku 授权的高级模式，不能在普通权限中绕过系统。
2. **必须手动授权**：被控端用户在每次新屏幕共享会话接受系统录屏授权；若需远程触控，用户需在设置中启用 NearCast 无障碍服务。锁定屏幕或主动停止会话时会停播。
3. **安全限制**：当前协议是明文 TCP + 6 位码，不抗 LAN 监听与中间人攻击；不要在公共 Wi-Fi 或敏感设备上使用。正式发布前必须加入设备身份认证、加密媒体与控制通道、连接频控/用户确认。
4. **视频链路为 MVP**：TCP 队头阻塞和每帧内存拷贝不适合极限低延迟场景；下一阶段替换为低缓冲加密 UDP/RTP、IDR 恢复及帧统计。
5. **连续滑动**：当前在手指抬起后提交一条完整滑动手势，不是每帧实时 MOVE；持续拖动、多点触控、输入法、旋转过程中会话自动重配待完善。
6. 目前只支持同一局域网可直接互访的设备，无公网和中继能力。Android 17 / target 37+ 需要接入新局域网权限 \`ACCESS_LOCAL_NETWORK\` 或系统设备选择流程；此原型 targetSdk 为 35。
7. 个别应用启用 \`FLAG_SECURE\` 后其画面不可捕获。

## 使用

1. 两台 Android 8.0+ 手机连到同一 Wi-Fi，禁用 AP/client isolation。
2. 安装相同版本的 NearCast APK，打开后等待在「附近设备」中自动发现。
3. 控制端选择被控端，输入被控端显示的 6 位配对码。
4. 被控端点击「开始共享」，接受系统录屏授权；需要远控时手动打开「设置控制权限」并启用 NearCast。
5. 控制端在画面上点击/滑动，底部支持返回、主页、最近任务。
6. 被控端可选打开「省电暗屏」；需额外授予「修改系统设置」权限，允许后回到应用再次开启。使用后点「停止共享」自动还原。

### 构建

Android Studio（JDK 17，Android SDK 35）打开仓库根目录并运行 \`app\`。

或使用 Gradle 8.10.2 / JDK 17 执行：

\`\`\`bash
gradle :app:assembleDebug
\`\`\`

CI 会上传 \`NearCast-debug\` APK artifact。

## 模块

\`\`\`text
app/
  MainActivity.kt              Compose 双角色交互
  NearCastRuntime.kt           当前进程会话管理
  network/NearbyDiscovery.kt   NSD 自动发现
  network/LanSession.kt        配对、会话与帧协议
  network/AvcSurfaceDecoder.kt 硬解码到 Surface
  capture/CaptureService.kt    录屏授权与前台服务
  capture/ScreenEncoder.kt     Surface 直通硬编码
  capture/DimScreen.kt         暗屏亮度控制/恢复
  input/RemoteInputService.kt  用户授权的无障碍手势
\`\`\`

## 下一步（按优先级）

- [ ] Android Actions/真实设备构建和 Android 8/12/14/15/16 兼容性验证
- [ ] 加密身份认证和用户明确授权远控会话
- [ ] 视频 UDP FastPath + 分片/FEC或NACK/请求 IDR + 丢帧重同步
- [ ] 帧率与端到端延迟监控（Glass-to-Glass / touch-to-display）
- [ ] 横竖屏动态 resize、编码器/解码器重配置
- [ ] 连续触控 / 多指手势，控制端键盘输入
- [ ] 真实关屏的高级授权扩展（仅在平台与授权方式允许时）
- [ ] Android 17 本地网络授权与发现 API 兼容

## License

MIT
