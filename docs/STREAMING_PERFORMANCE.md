# NearCast v0.5 — UI, client bitrate control and low-latency work

## User experience

**设备** is the entry point, containing:
- A compact local host card (PIN, connection state, start/stop capture, input permission).
- A single connection-mode selector: **同一 Wi-Fi / Wi-Fi Direct**.
- LAN: discovered peer list and manual IP fallback.
- Direct: role-based flow. Controlled phone chooses **共享这台手机** and creates a P2P group. Controller chooses **控制另一台** → search → join → connect to group owner → enter PIN.
- Clear connected/disconnected group state. The existing video transport remains usable over either network link.

**画质** shows:
- **Controller bitrate target** in Mbps, persisted for this app process and sent at connection time and during playback (on slider gesture completion).
- Controlled-device resolution, target FPS, encoder codec, UDP/TCP transport, optional playback audio.

**诊断** contains codec capability/probe results and detailed stream numbers that formerly crowded the home and playback screens. In the viewer, the top bar displays only codec and output FPS, with separate **码率** and **统计** actions, also accessible in fullscreen.

## Controller bitrate control

Protocol: \`NEARCAST/5\` (both devices must use the same APK). New reliable control message:
- \`SET_BITRATE=9\`, followed by signed big-endian int Mbps.
- Host validates/clamps to **2–60 Mbps**. Malicious/untrusted clients cannot request unbounded targets.
- The host applies \`MediaCodec.setParameters(Bundle(PARAMETER_KEY_VIDEO_BITRATE = Mbps × 1,000,000))\` to the active encoder.
- Initial \`MediaFormat.KEY_BIT_RATE\` also uses the last requested bitrate; default **10 Mbps** before any command.
- \`bitrateCapMbps\` is the codec *target* and a software-enforced upper bound on **requested bitrate**, not a guarantee that instantaneous wire traffic cannot exceed that rate: keyframes, video rate control, packetization and audio create bursts.
- Some OEM encoders ignore or reject runtime bitrate changes; report them in \`NearCastEncoder\` logcat and compare actual encoder Mbps with requested target.
- Adjustments take place over the session's authenticated-by-PIN **development TCP protocol**, which is currently NOT encrypted or MITM safe. Production use requires cryptographic authenticated connection and replay protection.

## Software bottlenecks investigated

1. **Decoder → Compose recompositions**: older code wrote \`decodedFrames.value\` on every video frame (up to 120 times/second). Now publishes frame totals once per second.
2. **UDP packet hot loop**: older code allocated a \`DatagramPacket\` and \`ByteBuffer\` per received fragment and traversed an assembly map per fragment. Now buffers are reused, cleanup runs only periodically, and frame assembly avoids an extra full-size copy.
3. **Static screen triggering IDR storms**: older code requested keyframes repeatedly when UDP had no packets for 800ms, even when the captured screen had no new frames. Now only incomplete aged frames cause an idle-time recovery request.
4. **Codec output presentation backlog**: playback adds optional **lowest-latency** mode that drains multiple already-decoded output buffers and presents the most recent, dropping obsolete *decoded output* only. It does **not** drop compressed P-frames (which would corrupt reference prediction). Alternative ordered presentation can be smoother but may queue frames and increase perceived latency.
5. **Sender video queue**: still bounded and requests a new IDR if it overflows. This prototype does not implement Moonlight's full UDP transport, selective loss recovery or packet pacing.

## Moonlight comparison

- Moonlight offers **Prefer lowest latency** (immediate rendering with dropping stale frames), **Balanced** (Choreographer pacing, up to one frame buffered), and **Prefer smoothest video** (keeps frames, at risk of large latency). NearCast now offers a basic lowest-latency output mode vs ordered release. This is **not** an implementation of Moonlight's complete balanced Choreographer scheduler.
- Sunshine's host bitrate can honor client bitrate or apply a server maximum: NearCast similarly clamps client requests and dynamically updates the host encoder, but does not yet implement a full adaptive congestion controller.
- Moonlight separates network loss, jitter, decode and render latency statistics. NearCast shows some corresponding FPS/queue/RTT/MBps metrics, but does not yet have true rendered-frame timestamps or capture-to-display P50/P95 latency. Frame completion and Surface display timing remain different measurements.
- Moonlight commonly runs with specialized platform capture/encoding (PC GPU, NVENC etc.). NearCast Android-to-Android is constrained by MediaProjection and target application's actual vsync/source FPS. Wi-Fi Direct cannot override a system-enforced 60 FPS source cap.

References:
- https://github.com/moonlight-stream/moonlight-docs/wiki/Frequently-Asked-Questions
- https://github.com/LizardByte/Sunshine/blob/master/docs/configuration.md
- https://github.com/moonlight-stream/moonlight-android

## Real-device acceptance tests (not yet completed)

- [ ] Verify Android CI APK build and both-device pairing using \`NEARCAST/5\`.
- [ ] From viewer choose **2, 6, 10, 20, 40 Mbps**, confirm host's target changes immediately and hardware encoder output Mbps responds on a changing scene.
- [ ] Compare lowest-latency vs ordered rendering in a 60Hz-source/120Hz-display case and a genuine 120FPS case.
- [ ] Compare 15-minute Wi-Fi and Wi-Fi Direct sustained sessions: encoder output PTS jitter, client RTT variance, queue overflows, IDR rates, decoding FPS, thermal decline.
- [ ] Test background/foreground return and device rotation; avoid black screen after decoder restart.
- [ ] For actual latency, collect glass-to-glass and touch-to-response measurements with an external high-speed camera.
- [ ] Harden session authentication/encryption before use on any non-isolated network.

**This is a development build, not a stable Moonlight-equivalent implementation.**


## v0.6 Stability: unexpected disconnects and low-FPS bursts

### Verified code issues and changes
- Previous implementation multiplexed UDP video sends, reliable TCP packet writes, audio chunks, heartbeat replies and codec metadata onto **one writer**. A large UDP IDR fragmented into many tiny datagrams could block TCP PONG, introducing apparent RTT jitter. UDP traffic now uses a dedicated writer/queue, while TCP prioritizes control/config packets. TCP-only mode still has head-of-line blocking and is intended for compatibility.
- Previous UDP Socket send exception escaped the shared writer and **closed the TCP connection**. Now UDP failures are handled as *video-stream recovery*, while TCP control remains connected.
- The previous host immediately stopped CaptureService on a TCP disconnect, requiring fresh user MediaProjection consent even when Wi-Fi recovered. Host now retains the capture for **15 seconds**, then safely stops the existing foreground service if still disconnected. A new client attaches to the existing stream metadata, receives codec configuration and requests a fresh IDR.
- The controller now retries at bounded intervals up to five times following an unexpected transport failure. Explicit user disconnect and incorrect PIN cancel retries. A TCP heartbeat watchdog detects half-open/stalled sockets after approximately 11/12 seconds. This does **not** avoid capture interruption if Android terminates MediaProjection itself, or if the app is force stopped.
- Encoder IDR and runtime bitrate calls now use their own single-threaded codec control executor and are limited so networking callbacks cannot synchronously block MediaCodec output.
- UDP sender sends large keyframes in micro-bursts of 16 datagrams, with a very short inter-burst pause. The receiver requests a larger socket buffer and modestly longer reorder/fragment-loss tolerance. These are heuristics pending true network measurement, not packet loss recovery or congestion control.
- Android low-latency Wi-Fi performance hint is scoped to active capture/viewer sessions. Per Android's official API it applies only while connected to an AP, the screen is on, and the app is foreground. It **is not guaranteed to affect Wi-Fi Direct**, and may consume more battery or reduce throughput.

### Debugging a remaining dropout
In the **诊断** tab, record:
- Host: actual encoded FPS, Mbps, writer queue depth, GOP re-syncs, *last host TCP disconnect reason*.
- Controller: decoded input/output FPS, receive Mbps, control RTT/jitter, queued frames, IDR requests, *last client disconnect reason* and reconnect state.

Read actual Android logs:
\`\`\`bash
adb logcat -v time -s NearCastHost NearCastClient NearCastUDP NearCastEncoder NearCastDecoder NearCastCapture NearCastWifi
\`\`\`

Interpretation:
- **Encoder FPS steady, UDP recovery/IDR rises, TCP RTT steady** → video fragment loss, receiver backlog or UDP send pressure; use H.265 720p/60, then compare 6/10/15 Mbps.
- **TCP RTT spikes then SocketTimeoutException/EOF** → actual control channel failure, network interface switch, routing or OS Wi-Fi policy; automatic reconnect may restore within 15 sec.
- **TCP stable, decoded output FPS falls / decoder queue fills** → hardware decoder or Surface/UI rendering queue; reduce resolution/bitrate and toggle minimum-latency presentation.
- **Host source/capture FPS falls to 60 while link RTT stable** → OS/application refresh/capture policy, not network bandwidth.

Do not claim root cause is definitively known until a two-device Logcat trace or time-correlated performance report is available.

### Safety
- The experimental NearCast protocol is still plaintext, PIN is not cryptographic device authentication. Testing only on user-authorized trusted isolated networks, never a public or sensitive network.
- Avoid hiding failures by silently extending queues indefinitely. If a session remains unresponsive past the recovery window, show the reason and let the user initiate a fresh screen-capture consent.
