# NearCast architecture (v0.1)

Device A (host)                                  Device B (controller)
MediaProjection                                  Compose AndroidView
  → VirtualDisplay → MediaCodec AVC              SurfaceView ← MediaCodec AVC
  → TCP H264 access unit stream ───────────────→ decoder output Surface
AccessibilityService ←──── TCP gesture actions ─ pointer capture

The HostServer owns an ephemeral random six-digit code and is advertised over
\`_nearcast._tcp.\` with Android NsdManager. RemoteClient explicitly connects
to the IP/port published by NSD; manual IP/port is also supported.

Wire protocol (both directions on one TCP connection):
- Handshake: DataOutputStream.writeUTF("NEARCAST/1"), writeUTF(pin).
  Host replies writeBoolean(accepted).
- Host → client TYPE 1: width:int32, height:int32.
- Host → client TYPE 2: flags:int32, ptsUs:int64, size:int32, bytes[size].
- Client → host TYPE 3: action:int32, x1/y1/x2/y2:float32, durationMs:int32.
  Action IDs: TAP=1, SWIPE=2, BACK=3, HOME=4, RECENTS=5.

All integer fields use Java DataInput/OutputStream big-endian encoding.
The transport is a proof-of-concept and unencrypted. Do not expose this port
outside a trusted local environment.

Screen-off design: ordinary Android app privileges cannot guarantee that a
MediaProjection capture continues while the physical screen is turned off.
DimScreen stores brightness/auto-brightness values, switches to minimum manual
brightness and acquires a legacy screen-dim wake lock while capturing. The
service restores values at session stop; app initialization attempts crash
recovery. The user must grant WRITE_SETTINGS explicitly.

Performance: Avoid Bitmap/ImageReader conversion. Both video MediaCodec
instances are surface-backed. TCP and a single decoder queue are temporary.
Add metrics for capture-to-encoder, wire RTT, decoder queue, and rendered frame.
Test 120FPS viability per device rather than assuming display Hz = capture FPS.

Known design issues for next changes: original decoder surface must be available
before the first AVC codec-config frame (the current client typically mounts it
before host starts streaming); stream startup should cache/replay CSD and request
IDR on reconnection, and decoder should handle dropping dependent frames safely.
The current process-global session owner is not a process-death recovery solution.
