package com.phoneshare.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原生屏幕共享子连接 pcS（仅发送视频，无音频、无 DataChannel）。
 * 信令由页面经 ctl 转发，本类不碰 KV。
 */
object NativeShare {
    private const val CAP_FPS = 30
    private const val LONG_SIDE_MAX = 1920

    @Volatile private var appCtx: Context? = null
    @Volatile private var eventSink: ((JSONObject) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val pendingCands = CopyOnWriteArrayList<IceCandidate>()
    private val factoryLock = Any()

    @Volatile private var egl: EglBase? = null
    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var helper: SurfaceTextureHelper? = null
    @Volatile private var source: VideoSource? = null
    @Volatile private var capturer: ScreenCapturerAndroid? = null
    @Volatile private var track: VideoTrack? = null
    @Volatile private var pc: PeerConnection? = null
    @Volatile private var capturerRunning = false
    @Volatile var pairCode: String = ""
        private set
    @Volatile private var captureGen = 0
    @Volatile private var answerSet = false
    @Volatile private var sid: String = ""
    @Volatile private var maxBps: Int = 2_500_000
    @Volatile private var cfgCodec: String = ""
    @Volatile private var lastLoggedCodec: String = ""
    /** 缓存最近一帧的 I420 拷贝，避免持有纹理帧阻塞采集 */
    private var lastI420: org.webrtc.VideoFrame.I420Buffer? = null
    private var lastRotation = 0
    private val frameLock = Any()
    private var frameSinkAttached = false
    private val frameSink = org.webrtc.VideoSink { frame ->
        try {
            frame.retain()
            val i420 = try { frame.buffer.toI420() } catch (_: Throwable) { null }
            val rot = frame.rotation
            frame.release()
            if (i420 == null) return@VideoSink
            val old: org.webrtc.VideoFrame.I420Buffer?
            synchronized(frameLock) {
                old = lastI420
                lastI420 = i420
                lastRotation = rot
            }
            old?.release()
        } catch (_: Throwable) {
            try { frame.release() } catch (_: Throwable) {}
        }
    }
    @Volatile private var lastRefreshAt = 0L
    @Volatile private var lastKfEncoded: Long = -1L
    @Volatile private var iceServers: List<PeerConnection.IceServer> = emptyList()
    @Volatile private var iceTransportPolicy: PeerConnection.IceTransportsType = PeerConnection.IceTransportsType.ALL
    @Volatile private var capW = 0
    @Volatile private var capH = 0
    @Volatile private var pendingProjData: Intent? = null
    private var serviceReadyTimeout: Runnable? = null
    private var orientThread: Thread? = null
    private val orientStop = AtomicBoolean(false)
    private var statsThread: Thread? = null
    private val statsStop = AtomicBoolean(false)

    fun attach(ctx: Context, sink: (JSONObject) -> Unit) {
        appCtx = ctx.applicationContext
        eventSink = sink
    }

    fun isCapturing(): Boolean = capturerRunning

    /** 重送最近一帧（I420 拷贝），促使编码器出关键帧 */
    fun refresh(): String {
        if (!capturerRunning) return "err:idle"
        val now = System.currentTimeMillis()
        if (now - lastRefreshAt < 1000) {
            Logx.add("refresh: 限频跳过")
            return "ok"
        }
        lastRefreshAt = now
        readKeyFramesEncoded { kfBefore ->
            try {
                val buf: org.webrtc.VideoFrame.I420Buffer?
                val rot: Int
                synchronized(frameLock) {
                    buf = lastI420
                    rot = lastRotation
                    buf?.retain()
                }
                if (buf != null) {
                    var nf: org.webrtc.VideoFrame? = null
                    try {
                        nf = org.webrtc.VideoFrame(buf, rot, System.nanoTime())
                        source?.capturerObserver?.onFrameCaptured(nf)
                        try { nf.release() } catch (_: Throwable) {}
                    } catch (e: Throwable) {
                        Logx.add("refresh frame: " + e.message)
                        // VideoFrame 构造成功后由 nf.release() 管理 buffer；仅未创建成功时释放 buf
                        if (nf != null) {
                            try { nf.release() } catch (_: Throwable) {}
                        } else {
                            try { buf.release() } catch (_: Throwable) {}
                        }
                    }
                } else {
                    Logx.add("refresh: 无缓存帧")
                }
                try {
                    val src = source
                    if (src != null && capW > 0 && capH > 0) {
                        src.adaptOutputFormat((capW * 0.95).toInt().coerceAtLeast(2), (capH * 0.95).toInt().coerceAtLeast(2), CAP_FPS)
                        main.postDelayed({
                            try { src.adaptOutputFormat(capW, capH, CAP_FPS) } catch (_: Throwable) {}
                        }, 400)
                    }
                } catch (_: Throwable) {}
            } catch (e: Throwable) {
                Logx.add("refresh: " + e.message)
            }
            main.postDelayed({
                readKeyFramesEncoded { kfAfter ->
                    Logx.add("refresh: 方式=b+i420 kf前=$kfBefore kf后=$kfAfter")
                }
            }, 800)
        }
        return "ok"
    }

    private fun readKeyFramesEncoded(cb: (Long) -> Unit) {
        val p = pc
        if (p == null) {
            cb(-1L)
            return
        }
        try {
            p.getStats { report ->
                var kf = -1L
                try {
                    for (s in report.statsMap.values) {
                        if (s.type == "outbound-rtp" && s.members["kind"]?.toString() == "video") {
                            (s.members["keyFramesEncoded"] as? Number)?.toLong()?.let { kf = it }
                        }
                    }
                } catch (_: Throwable) {}
                main.post { cb(kf) }
            }
        } catch (_: Throwable) {
            cb(-1L)
        }
    }


    fun currentSize(): Pair<Int, Int> = Pair(capW, capH)

    /** 系统录屏授权结果（Activity 回调后调用） */
    fun onProjectionResult(resultCode: Int, data: Intent?) {
        val ctx = appCtx ?: run {
            Logx.add("NativeShare: onProjectionResult 无 Context")
            return
        }
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            Logx.add("NativeShare: 用户拒绝或取消录屏授权")
            emit(JSONObject().put("t", "denied"))
            return
        }
        pendingProjData = data
        try {
            ShareService.startForNative(ctx, data)
            // 等 ShareService 进前台后 onServiceReady 再采集
            val timeout = Runnable {
                if (pendingProjData != null) {
                    Logx.add("NativeShare: 等待前台服务超时")
                    pendingProjData = null
                    stopInternal("error")
                    try { ShareService.stopNative(ctx) } catch (_: Throwable) {}
                    emit(JSONObject().put("t", "denied"))
                }
            }
            serviceReadyTimeout = timeout
            main.postDelayed(timeout, 5000L)
        } catch (e: Throwable) {
            Logx.add("NativeShare 启动服务失败: " + e.message)
            pendingProjData = null
            stopInternal("error")
            try { ShareService.stopNative(ctx) } catch (_: Throwable) {}
            emit(JSONObject().put("t", "denied"))
        }
    }

    /** 前台服务无法启动时由 ShareService 调用 */
    fun failStart() {
        pendingProjData = null
        serviceReadyTimeout?.let { main.removeCallbacks(it) }
        serviceReadyTimeout = null
        emit(JSONObject().put("t", "denied"))
        appCtx?.let {
            try { ShareService.stopNative(it) } catch (_: Throwable) {}
        }
    }

    /** ShareService 前台就绪后调用 */
    fun onServiceReady() {
        val ctx = appCtx ?: return
        val data = pendingProjData ?: return
        pendingProjData = null
        serviceReadyTimeout?.let { main.removeCallbacks(it) }
        serviceReadyTimeout = null
        try {
            startCapture(ctx, data)
            try {
                val code = ControlHandler.onSessionStart()
                pairCode = code
                // 配对码不写日志，仅通知栏展示
                try { ShareService.refreshNotification(ctx) } catch (_: Throwable) {}
            } catch (e: Throwable) {
                Logx.add("ControlHandler.onSessionStart 失败: " + e.message)
            }
            emit(JSONObject().put("t", "started").put("w", capW).put("h", capH))
            Logx.add("NativeShare: 采集已启动 ${capW}x${capH}")
        } catch (e: Throwable) {
            Logx.add("NativeShare 启动采集失败: " + e.message)
            stopInternal("error")
            try { ShareService.stopNative(ctx) } catch (_: Throwable) {}
            emit(JSONObject().put("t", "denied"))
        }
    }

    fun prepareIce(json: JSONObject) {
        maxBps = json.optInt("maxBps", 2_500_000)
        cfgCodec = ""
        lastLoggedCodec = ""
        lastKfEncoded = -1L
        try {
            var c = json.optString("codec", "").trim()
            if (c.startsWith("video/", ignoreCase = true)) c = c.substring(6)
            c = c.uppercase()
            if (c == "AV1X") c = "AV1"
            cfgCodec = c
        } catch (_: Throwable) { cfgCodec = "" }
        val ice = json.optJSONObject("ice") ?: return
        val policy = ice.optString("policy", "all")
        iceTransportPolicy = if (policy == "relay") PeerConnection.IceTransportsType.RELAY
        else PeerConnection.IceTransportsType.ALL
        val list = ArrayList<PeerConnection.IceServer>()
        val arr = ice.optJSONArray("iceServers") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val urls = o.optJSONArray("urls")
            val b = PeerConnection.IceServer.builder(
                if (urls != null) {
                    (0 until urls.length()).map { urls.getString(it) }
                } else {
                    listOf(o.optString("urls"))
                }
            )
            val u = o.optString("username", "")
            val c = o.optString("credential", "")
            if (u.isNotEmpty()) b.setUsername(u)
            if (c.isNotEmpty()) b.setPassword(c)
            list.add(b.createIceServer())
        }
        iceServers = list
    }

    fun createOffer(newSid: String) {
        sid = newSid
        val ctx = appCtx ?: run {
            Logx.add("NativeShare.createOffer: 无 Context")
            return
        }
        if (!capturerRunning || track == null) {
            Logx.add("NativeShare.createOffer: 采集未就绪")
            return
        }
        closePcOnly()
        answerSet = false
        pendingCands.clear()
        ensureFactory(ctx)
        val f = factory ?: return
        val tr = track ?: return

        val cfg = PeerConnection.RTCConfiguration(iceServers)
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        cfg.iceTransportsType = iceTransportPolicy
        val obs = object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                val name = s?.name ?: return
                when (name) {
                    "CONNECTED", "COMPLETED" -> emit(JSONObject().put("t", "state").put("s", "connected"))
                    "FAILED" -> emit(JSONObject().put("t", "state").put("s", "failed"))
                    "CLOSED" -> emit(JSONObject().put("t", "state").put("s", "closed"))
                }
            }
            override fun onStandardizedIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate?) {
                if (c == null) return
                val jo = JSONObject()
                    .put("sdpMid", c.sdpMid ?: "")
                    .put("sdpMLineIndex", c.sdpMLineIndex)
                    .put("candidate", c.sdp)
                emit(JSONObject().put("t", "cand").put("c", jo))
            }
            override fun onIceCandidatesRemoved(a: Array<out IceCandidate>?) {}
            override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent?) {}
            override fun onAddStream(s: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(s: org.webrtc.MediaStream?) {}
            override fun onDataChannel(d: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: org.webrtc.RtpReceiver?, a: Array<out org.webrtc.MediaStream>?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
            override fun onRemoveTrack(receiver: org.webrtc.RtpReceiver?) {}
        }
        val p = f.createPeerConnection(cfg, obs) ?: return
        pc = p
        val sender = p.addTrack(tr, listOf("screen"))
        for (t in p.transceivers) {
            if (t.sender.id() == sender.id()) {
                try { t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY } catch (_: Throwable) {}
            }
        }
        tuneSender(sender)

        val mc = MediaConstraints()
        p.createOffer(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {
                if (d == null) return
                val sdpText = preferCodecSdp(d.description, cfgCodec)
                val local = SessionDescription(SessionDescription.Type.OFFER, sdpText)
                p.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(x: SessionDescription?) {}
                    override fun onSetSuccess() {
                        emit(JSONObject().put("t", "sdp").put("type", "offer").put("sdp", sdpText))
                        Logx.add("NativeShare offer SDP 已发送（H264 优先调整后）")
                    }
                    override fun onCreateFailure(e: String?) {}
                    override fun onSetFailure(e: String?) { Logx.add("setLocalDescription 失败: $e") }
                }, local)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String?) { Logx.add("createOffer 失败: $e") }
            override fun onSetFailure(e: String?) {}
        }, mc)
        startStatsLoop()
    }

    fun onSignal(json: JSONObject) {
        val t = json.optString("t")
        val p = pc ?: return
        when (t) {
            "answer" -> {
                val sdp = json.optString("sdp")
                if (sdp.isEmpty()) return
                val d = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                p.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(x: SessionDescription?) {}
                    override fun onSetSuccess() {
                        answerSet = true
                        for (c in pendingCands) {
                            try { p.addIceCandidate(c) } catch (_: Throwable) {}
                        }
                        pendingCands.clear()
                    }
                    override fun onCreateFailure(e: String?) {}
                    override fun onSetFailure(e: String?) { Logx.add("setRemoteAnswer 失败: $e") }
                }, d)
            }
            "cand" -> {
                val c = json.optJSONObject("c") ?: return
                val cand = IceCandidate(
                    c.optString("sdpMid", ""),
                    c.optInt("sdpMLineIndex", 0),
                    c.optString("candidate")
                )
                if (!answerSet) {
                    pendingCands.add(cand)
                } else {
                    try { p.addIceCandidate(cand) } catch (_: Throwable) {}
                }
            }
        }
    }

    /** 只关 pcS，保留采集（对方刷新） */
    fun resetPeer() {
        closePcOnly()
    }

    fun stop(why: String = "user", notifyService: Boolean = true) {
        val was = capturerRunning || pc != null
        stopInternal(why)
        if (notifyService) {
            appCtx?.let { ShareService.stopNative(it) }
        }
        if (was) emit(JSONObject().put("t", "stopped").put("why", why))
    }

    // ----- internal -----

    private fun startCapture(ctx: Context, data: Intent) {
        // 若已有旧采集器，先释放再创建
        try { capturer?.stopCapture() } catch (_: Throwable) {}
        try { capturer?.dispose() } catch (_: Throwable) {}
        capturer = null
        capturerRunning = false
        try {
            synchronized(frameLock) {
                lastI420?.release()
                lastI420 = null
            }
        } catch (_: Throwable) {}
        ensureFactory(ctx)
        val f = factory ?: throw IllegalStateException("factory null")
        val eglBase = egl ?: throw IllegalStateException("egl null")
        if (source == null) {
            source = f.createVideoSource(true)
        }
        if (helper == null) {
            helper = SurfaceTextureHelper.create("NativeCap", eglBase.eglBaseContext)
        }
        val gen = ++captureGen
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                main.post {
                    if (gen == captureGen && capturerRunning) stop("system", notifyService = true)
                }
            }
        }
        val cap = ScreenCapturerAndroid(data, cb)
        capturer = cap
        val src = source!!
        val h = helper!!
        cap.initialize(h, ctx.applicationContext, src.capturerObserver)
        val size = captureSize(ctx)
        capW = size.first
        capH = size.second
        // 长边 ≤ 1920
        cap.startCapture(capW, capH, CAP_FPS)
        try {
            src.adaptOutputFormat(capW, capH, CAP_FPS)
        } catch (_: Throwable) {}
        if (track == null) {
            val tr = f.createVideoTrack("screen0", src)
            tr.setEnabled(true)
            try {
                tr.addSink(frameSink)
                frameSinkAttached = true
            } catch (e: Throwable) {
                Logx.add("addSink: " + e.message)
            }
            track = tr
        }
        capturerRunning = true
        startOrientLoop(ctx)
        Logx.add("NativeShare 采集开始 ${capW}x${capH}")
    }

    private fun captureSize(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        var w = dm.widthPixels
        var h = dm.heightPixels
        val longSide = maxOf(w, h)
        if (longSide > LONG_SIDE_MAX) {
            val s = LONG_SIDE_MAX.toDouble() / longSide
            w = (w * s).toInt()
            h = (h * s).toInt()
        }
        w -= w % 2
        h -= h % 2
        return Pair(w, h)
    }

    private fun startOrientLoop(ctx: Context) {
        orientStop.set(true)
        orientThread?.interrupt()
        orientStop.set(false)
        val t = Thread {
            while (!orientStop.get()) {
                try {
                    Thread.sleep(1000)
                    if (!capturerRunning) continue
                    val size = captureSize(ctx)
                    if (size.first != capW || size.second != capH) {
                        capW = size.first
                        capH = size.second
                        try {
                            capturer?.changeCaptureFormat(capW, capH, CAP_FPS)
                            source?.adaptOutputFormat(capW, capH, CAP_FPS)
                        } catch (_: Throwable) {}
                        emit(JSONObject().put("t", "size").put("w", capW).put("h", capH))
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {}
            }
        }
        t.name = "native-orient"
        t.isDaemon = true
        orientThread = t
        t.start()
    }

    private fun startStatsLoop() {
        statsStop.set(true)
        statsThread?.interrupt()
        statsStop.set(false)
        val t = Thread {
            while (!statsStop.get()) {
                try {
                    Thread.sleep(5000)
                    val p = pc ?: continue
                    p.getStats { report ->
                        var sent = 0L
                        var rec = 0L
                        var pk = 0L
                        var relay = false
                        var pair = ""
                        var avail: Long? = null
                        var rttMs: Long? = null
                        var nack: Long? = null
                        var pli: Long? = null
                        var kf: Long? = null
                        var lost: Double? = null
                        var encName: String? = null
                        var encImpl: String? = null
                        var outW: Int? = null
                        var outH: Int? = null
                        var outFps: Double? = null
                        var outLim: String? = null
                        val map = report.statsMap
                        var selectedId: String? = null
                        for (s in map.values) {
                            if (s.type == "transport") {
                                val sid = s.members["selectedCandidatePairId"]?.toString()
                                if (!sid.isNullOrEmpty()) selectedId = sid
                            }
                        }
                        var pairStat: org.webrtc.RTCStats? = null
                        if (selectedId != null) pairStat = map[selectedId]
                        if (pairStat == null) {
                            for (s in map.values) {
                                if (s.type == "candidate-pair" && s.members["nominated"] == true) {
                                    pairStat = s
                                    break
                                }
                            }
                        }
                        if (pairStat == null) {
                            for (s in map.values) {
                                if (s.type == "candidate-pair" && s.members["state"]?.toString() == "succeeded") {
                                    pairStat = s
                                }
                            }
                        }
                        if (pairStat != null) {
                            pair = pairStat.id
                            val local = pairStat.members["localCandidateId"]?.toString() ?: ""
                            for (c in map.values) {
                                if (c.id == local && c.members["candidateType"]?.toString() == "relay") {
                                    relay = true
                                }
                            }
                            sent = (pairStat.members["bytesSent"] as? Number)?.toLong() ?: 0L
                            rec = (pairStat.members["bytesReceived"] as? Number)?.toLong() ?: 0L
                            pk = (pairStat.members["packetsSent"] as? Number)?.toLong() ?: 0L
                            (pairStat.members["availableOutgoingBitrate"] as? Number)?.toLong()?.let { avail = it }
                            (pairStat.members["currentRoundTripTime"] as? Number)?.toDouble()?.let {
                                rttMs = (it * 1000.0).toLong()
                            }
                        }
                        for (s in map.values) {
                            if (s.type == "outbound-rtp" && s.members["kind"]?.toString() == "video") {
                                nack = (s.members["nackCount"] as? Number)?.toLong()
                                pli = (s.members["pliCount"] as? Number)?.toLong()
                                kf = (s.members["keyFramesEncoded"] as? Number)?.toLong()
                                if (kf != null) lastKfEncoded = kf!!
                                outW = (s.members["frameWidth"] as? Number)?.toInt()
                                outH = (s.members["frameHeight"] as? Number)?.toInt()
                                outFps = (s.members["framesPerSecond"] as? Number)?.toDouble()
                                outLim = s.members["qualityLimitationReason"]?.toString()
                                val codecId = s.members["codecId"]?.toString()
                                if (!codecId.isNullOrEmpty()) {
                                    val cs = map[codecId]
                                    val mime = cs?.members?.get("mimeType")?.toString() ?: ""
                                    if (mime.startsWith("video/")) encName = mime.substring(6)
                                    else if (mime.isNotEmpty()) encName = mime
                                    encImpl = s.members["encoderImplementation"]?.toString()
                                } else {
                                    encImpl = s.members["encoderImplementation"]?.toString()
                                }
                            }
                        }
                        for (s in map.values) {
                            if (s.type == "remote-inbound-rtp" && s.members["kind"]?.toString() == "video") {
                                (s.members["fractionLost"] as? Number)?.toDouble()?.let { lost = it }
                            }
                        }
                        if (encName != null && encName != lastLoggedCodec) {
                            Logx.add("NativeShare: 共享编码=$encName 编码器=${encImpl ?: "?"} 请求=${if (cfgCodec.isEmpty()) "空" else cfgCodec}")
                            lastLoggedCodec = encName!!
                        }
                        val jo = JSONObject().put("t", "stats")
                            .put("pair", pair)
                            .put("relay", relay)
                            .put("sent", sent)
                            .put("rec", rec)
                            .put("pk", pk)
                        if (encName != null) jo.put("enc", encName)
                        if (encImpl != null) jo.put("impl", encImpl)
                        if (outW != null) jo.put("w", outW)
                        if (outH != null) jo.put("h", outH)
                        if (outFps != null) jo.put("fps", outFps)
                        if (outLim != null) jo.put("lim", outLim)
                        if (nack != null) jo.put("nack", nack)
                        if (pli != null) jo.put("pli", pli)
                        if (kf != null) jo.put("kf", kf)
                        if (lost != null) jo.put("lost", lost)
                        if (rttMs != null) jo.put("rtt", rttMs)
                        if (avail != null) jo.put("avail", avail)
                        emit(jo)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {}
            }
        }
        t.name = "native-stats"
        t.isDaemon = true
        statsThread = t
        t.start()
    }

    private fun tuneSender(sender: org.webrtc.RtpSender) {
        try {
            val params = sender.parameters
            if (params.encodings.isNotEmpty()) {
                params.encodings[0].maxBitrateBps = maxBps
                params.encodings[0].maxFramerate = CAP_FPS
            }
            try {
                val m = params.javaClass.getMethod("setDegradationPreference", RtpParameters.DegradationPreference::class.java)
                m.invoke(params, RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION)
            } catch (_: Throwable) {
                try {
                    params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                } catch (_: Throwable) {}
            }
            sender.parameters = params
        } catch (e: Throwable) {
            Logx.add("tuneSender: " + e.message)
        }
        try {
            // contentHint=text（部分版本）
            track?.setEnabled(true)
        } catch (_: Throwable) {}
    }

    private fun preferH264(p: PeerConnection) {
        // 实际在 SDP 层 preferH264Sdp 处理
    }

    /** 把 H264（及 apt 指向它的 rtx）挪到 m=video 格式列表最前 */
    private fun preferCodecSdp(sdp: String, nameIn: String): String {
        val name = if (nameIn.isEmpty()) "H264" else nameIn
        try {
            val sep = if (sdp.contains("\r\n")) "\r\n" else "\n"
            val raw = sdp.split(sep).toMutableList()
            var i = 0
            while (i < raw.size) {
                val line = raw[i]
                if (line.startsWith("m=video")) {
                    val parts = line.split(" ").toMutableList()
                    if (parts.size < 4) return sdp
                    val ptsWanted = ArrayList<String>()
                    val rtxFor = HashMap<String, String>() // rtxPt -> apt
                    for (l in raw) {
                        if (l.startsWith("a=rtpmap:")) {
                            // a=rtpmap:<pt> NAME/90000
                            val rest = l.substring(9)
                            val sp = rest.split(" ")
                            if (sp.size < 2) continue
                            val pt = sp[0]
                            val codecPart = sp[1]
                            val cn = codecPart.substringBefore("/").uppercase()
                            val match = cn == name || (name == "AV1" && (cn == "AV1" || cn == "AV1X"))
                            if (match) ptsWanted.add(pt)
                        }
                        if (l.startsWith("a=fmtp:") && l.contains("apt=")) {
                            val rest = l.substring(7)
                            val pt = rest.split(" ")[0]
                            val aptIdx = rest.indexOf("apt=")
                            if (aptIdx >= 0) {
                                var apt = rest.substring(aptIdx + 4)
                                val cut = apt.indexOfAny(charArrayOf(';', ' '))
                                if (cut >= 0) apt = apt.substring(0, cut)
                                rtxFor[pt] = apt
                            }
                        }
                    }
                    var useName = name
                    if (ptsWanted.isEmpty() && name != "H264") {
                        Logx.add("NativeShare: 本机不支持 $name，回退 H264 优先")
                        useName = "H264"
                        for (l in raw) {
                            if (l.startsWith("a=rtpmap:")) {
                                val rest = l.substring(9)
                                val sp = rest.split(" ")
                                if (sp.size < 2) continue
                                val pt = sp[0]
                                val cn = sp[1].substringBefore("/").uppercase()
                                if (cn == "H264") ptsWanted.add(pt)
                            }
                        }
                    }
                    if (ptsWanted.isEmpty()) {
                        if (useName == "H264" || name.isEmpty()) {
                            Logx.add("NativeShare: SDP 无 H264，保持原序")
                        } else {
                            Logx.add("NativeShare: SDP 无 $useName，保持原序")
                        }
                        return sdp
                    }
                    val media = parts.subList(3, parts.size).toMutableList()
                    val preferred = ArrayList<String>()
                    for (pt in ptsWanted) {
                        if (media.remove(pt)) preferred.add(pt)
                        for ((rtx, apt) in rtxFor) {
                            if (apt == pt && media.remove(rtx)) preferred.add(rtx)
                        }
                    }
                    preferred.addAll(media)
                    raw[i] = (parts.subList(0, 3) + preferred).joinToString(" ")
                    if (nameIn.isEmpty() || name == "H264") {
                        Logx.add("NativeShare: SDP 已优先 H264 pts=" + ptsWanted.joinToString(","))
                    } else {
                        Logx.add("NativeShare: SDP 已优先 $useName pts=" + ptsWanted.joinToString(","))
                    }
                    break
                }
                i++
            }
            return raw.joinToString(sep)
        } catch (e: Throwable) {
            Logx.add("preferCodecSdp: " + e.message)
            return sdp
        }
    }

    private fun ensureFactory(ctx: Context) {
        synchronized(factoryLock) {
            if (factory != null) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                    .createInitializationOptions()
            )
            val eglBase = EglBase.create()
            egl = eglBase
            val enc = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            val dec = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(enc)
                .setVideoDecoderFactory(dec)
                .createPeerConnectionFactory()
        }
    }

    private fun closePcOnly() {
        statsStop.set(true)
        statsThread?.interrupt()
        statsThread = null
        try { pc?.close() } catch (_: Throwable) {}
        try { pc?.dispose() } catch (_: Throwable) {}
        pc = null
        answerSet = false
        pendingCands.clear()
    }

    private fun stopInternal(why: String) {
        Logx.add("NativeShare stop: $why")
        captureGen++
        pendingProjData = null
        serviceReadyTimeout?.let { main.removeCallbacks(it) }
        serviceReadyTimeout = null
        try { ControlHandler.onSessionEnd() } catch (_: Throwable) {}
        pairCode = ""
        try { MarkOverlay.hide() } catch (_: Throwable) {}
        orientStop.set(true)
        orientThread?.interrupt()
        orientThread = null
        closePcOnly()
        capturerRunning = false
        try {
            synchronized(frameLock) {
                lastI420?.release()
                lastI420 = null
            }
        } catch (_: Throwable) {}
        try { capturer?.stopCapture() } catch (_: Throwable) {}
        try { capturer?.dispose() } catch (_: Throwable) {}
        capturer = null
        try { track?.dispose() } catch (_: Throwable) {}
        track = null
        try { source?.dispose() } catch (_: Throwable) {}
        source = null
        try { helper?.dispose() } catch (_: Throwable) {}
        helper = null
        // factory/egl 可复用，不销毁
    }

    private fun emit(o: JSONObject) {
        val sink = eventSink ?: return
        main.post {
            try { sink(o) } catch (e: Throwable) {
                Logx.add("NativeShare emit: " + e.message)
            }
        }
    }
}
