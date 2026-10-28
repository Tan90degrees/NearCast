# NearCast v0.2 验证计划

## CI
- [ ] \`gradle :app:assembleDebug\` 构建成功并上传 APK
- [ ] 目标机型 Android 10 / 12 / 14 / 15 / 16 构建与权限检查

## 双机真机
1. 两端安装同版本 APK，配对后共享 H.264 720p/60；确认实时图像与触控。
2. 各档 H.264/H.265/AV1 × 720p/1080p/原生 × 60/90/120 FPS 验证实际视频帧率、热衰减和自动回退。
3. Wi-Fi 干扰、移动到边缘信号、短时路由抖动：确认编码端网络队列溢出后自动恢复 IDR，不永久冻结。
4. 控制端 Home / 切换应用 / 锁屏再解锁 / 横竖屏 / 返回 NearCast：确认视频恢复（接收音频可选）、触控正常。
5. 全屏与退出全屏时检查视频比例与触控坐标；切换过程不要丢失会话。
6. Android 10+ 允许音频的媒体应用，测试音频开关；对 DRM、通话、禁止音频采集的应用确认有明确限制。
7. 被控端主动停止共享 / 拔掉 Wi-Fi / 控制端异常退出：视频和 AudioRecord / AudioTrack 都必须释放。
8. 音频权限拒绝时允许视频继续共享；不能绕过系统授权。
9. 用高速摄像机分别量化 Glass-to-Glass、点击到画面反馈，而不是以编码器设定 FPS 代替真实 FPS。

## 已知待优化
- 将实验性自定义 UDP 分片协议升级为带设备身份认证、可靠协商、加密以及更完善的丢包/拥塞控制的 UDP/RTP/SRTP；当前测试版的 TCP 和 UDP 都是明文
- Codec-config 缓存/IDR 恢复在更多 OEM 编码器上的兼容验证
- Opus 编码、音视频同步、低延迟缓冲、音频路由与蓝牙输出处理
- 横竖屏动态重配与 Android 14+ 单次 MediaProjection VirtualDisplay 授权适配
- 可靠连续手势和多指控制
- Android 17 本地网络权限适配

状态：代码实现，不是已完成测试的声明。

## 120 FPS & "1000 Mbps but stutters" diagnostic matrix

Run a real scrolling/animation workload on the **captured** device. An idle
screen does not necessarily produce video frames at the display refresh rate.
Compare the statistics displayed in both apps over 15–30 seconds.

| Host physical / virtual display | Host actual encoded | Client decoder submitted | Client decoded output | Interpretation |
| --- | --- | --- | --- | --- |
| 120 / 60 Hz | ~60 FPS | ~60 | ~60 | VirtualDisplay / capture path or OS refresh policy is likely limiting output; setting MediaCodec to 120 is insufficient |
| 120 / 120 Hz | ~60 FPS | ~60 | ~60 | Encoder input/capture production or codec performance; test device restrictions and MediaProjection output PTS |
| 120 / 120 Hz | ~120 FPS | ~60 | ~60 | Look at sender backlog, receiver receive Mbps, decode queue overflows and IDR requests |
| 120 / 120 Hz | ~120 FPS | ~120 | ~60 | Decoder output / render bottleneck; inspect codec support and receiving display mode |
| 120 / 120 Hz | ~120 FPS | ~120 | ~120 | Pipeline outputs 120, but screen/display refresh and glass-to-glass latency still need measurement |

Important: host "virtual display Hz" is a reported property; it does not
guarantee captured frames actually arrive at that rate. Client "decoded
output FPS" counts output buffers released for rendering, NOT verified
on-screen presentation at each VSYNC.

Throughput of 1000 Mbps measures capacity, not per-packet latency, queue delay
or UDP burst loss. This preview uses 7–14 Mbps nominal video bitrate, so a
1 Gbps link should have ample average capacity; inspect burst queue behavior,
packet loss and decode time before increasing bitrate.

Current fixes:
- Network reader threads no longer call MediaCodec.enqueue/dequeue directly.
  Decoder input has a dedicated queue and worker.
- UDP sender reuses a 1200-byte packet buffer per video access unit instead
  of creating a buffer+DatagramPacket for every fragment.
- Shows host physical/virtual refresh rate, *actual* encoded FPS/bitrate,
  network writer Mbps and depth, client decode input/output FPS and queue,
  incoming Mbps and IDR requests.
- Receiver UI requests the highest same-resolution supported display mode.
  Android/OEM may still override it.

Suggested experiments:
1. Both devices plugged in, disabled battery saver, system display refresh set
   to 120 Hz where supported; run a sustained moving animation.
2. Set H.264, 720p, 120 FPS. Compare TCP and UDP modes separately.
3. Repeat with system refresh forced to 60 Hz as a negative control.
4. Record 10-second stats / Logcat and look for encoder FPS (60 cap) versus
   decoder queue overflows (network/decode throughput cap).
5. Do not increase bitrate until you can identify the failing pipeline stage.

Known design limitations: no 120-FPS guarantee for public MediaProjection
clients; no on-device *true* on-screen frame counting; experimental UDP has no
congestion control, FEC, encryption, authenticated handshake or full latency
telemetry. Neither codec requested FPS nor screen Hz should be called "actual
end-to-end FPS".


## 编解码器探测与刷新率切换专项验证

1. 两台手机分别启动 NearCast，读取本机 H.264/H.265/AV1 编码和解码器名称、[HW]/[SW] 标签、Surface 编码能力及 720p120 声明。
2. 不共享屏幕时点击「实测初始化」，检查 configure/start 的成功/失败标签；确认共享中该操作不可触发。
3. A 配对 B 后检查 A 显示 B 支持的解码格式，并测试选择不兼容格式是否被禁止。
4. 在 A 选择 AV1，检查运行中实际编码器名称。必须不会选择 software/Google AV1；如没有硬件 AV1 应优先回退 HEVC（若 B 支持）。
5. A 选择 H.265 + 120 FPS，先在桌面滚动，再进入已知让屏幕降至 60 Hz 的 App。记录 A 物理/虚拟 Hz、编码 FPS、平均 PTS 间隔及帧间隔变化量；与 B 接收/解码 FPS 对照。
6. 比较 UDP 重排序更改前后的 IDR 请求增量与明显卡顿次数；网络带宽高并不排除短时乱序/丢包。
7. 同时验证 H.264、H.265 和 AV1 编码的实际端到端时延；configure/start 成功不是时延测试，尤其 AV1 要进行真机 Glass-to-Glass 测量。
