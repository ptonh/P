package com.phoneshare.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.net.Uri
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 手机共享屏幕（M2）：手机当 offerer（创建房间），网页当 answerer（加入）。
 * 信令沿用 Worker 的 /join /put /poll /leave；控制通道沿用 negotiated id=0 的 "ctl"。
 * 观看者离开后自动重新等待下一个观看者，屏幕采集不中断。
 */
class RtcSession(
    private val ctx: Context,
    private val cfg: Cfg,
    private val projData: Intent,
    private val onFinished: (String) -> Unit
) {
    companion object {
        const val FPS = 30
        @Volatile
        private var factoryInited = false
        @Volatile
        var pairCode: String = ""
    }

    // 每一次“等一个观看者 → 连接 → 观看者离开”的状态
    private class Sess(val cid: String) {
        val cands = JSONArray()
        @Volatile var gatherDone = false
        @Volatile var hasRelay = false
        @Volatile var dirty = false
        @Volatile var iceState = ""
        @Volatile var dcOpen = false
        @Volatile var lastRx = System.currentTimeMillis()
        @Volatile var peerBye = false
        @Volatile var peerAway = false
        @Volatile var disconnectedAt = 0L
        var ver = 1
        var offer: JSONObject? = null
        var lastPutAt = 0L
        var answered = false
        var peerCid = ""
        var peerCandCount = 0
        var lastBytes = -1.0
        var lastBytesAt = 0L
    }

    @Volatile private var closed = false
    @Volatile private var finishReason: String? = null
    private var thread: Thread? = null

    // 整个共享期间一直存在的媒体对象
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var capW = 0
    private var capH = 0
    @Volatile private var curChan: DataChannel? = null

    private fun sendScr() {
        sendDc(curChan, "scr:{\"w\":" + capW + ",\"h\":" + capH + "}")
    }

    fun start() {
        val t = Thread { runAll() }
        t.name = "share-main"
        thread = t
        t.start()
    }

    fun stop(reason: String) {
        if (finishReason == null) finishReason = reason
        closed = true
    }

    private fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
        }
    }

    private fun runAll() {
        var reason = "已结束"
        try {
            initMedia()
            while (!closed) {
                val r = runSession()
                if (r != "peer-left") {
                    reason = r
                    break
                }
                if (!closed) {
                    Logx.add("观看端已离开，重新等待新的观看者…")
                    sleepMs(1500)
                }
            }
        } catch (e: Throwable) {
            reason = "出错: " + e.javaClass.simpleName + ": " + e.message
            Logx.add(reason)
        } finally {
            cleanupAll()
            val fr = finishReason
            onFinished(if (fr != null) fr else reason)
        }
    }

    // ---------------------------------------------------------------- 屏幕采集

    private fun realSize(): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun captureSize(): Pair<Int, Int> {
        val real = realSize()
        val rw = real.first
        val rh = real.second
        val shortSide = minOf(rw, rh)
        var w = rw
        var h = rh
        val target = cfg.shortSide
        if (target > 0 && target < shortSide) {
            val s = target.toDouble() / shortSide.toDouble()
            w = (rw * s).toInt()
            h = (rh * s).toInt()
        }
        w -= w % 2
        h -= h % 2
        return Pair(w, h)
    }

    private fun initMedia() {
        if (!factoryInited) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                    .createInitializationOptions()
            )
            factoryInited = true
        }
        val eglBase = EglBase.create()
        egl = eglBase
        val enc = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val dec = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(enc)
            .setVideoDecoderFactory(dec)
            .createPeerConnectionFactory()
        factory = f
        val src = f.createVideoSource(true)
        source = src
        val h = SurfaceTextureHelper.create("ScreenCap", eglBase.eglBaseContext)
        helper = h
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                Logx.add("系统停止了屏幕采集（通知栏或系统关闭了共享）")
                stop("系统停止了屏幕采集")
            }
        }
        val cap = ScreenCapturerAndroid(projData, cb)
        capturer = cap
        cap.initialize(h, ctx.applicationContext, src.capturerObserver)
        val size = captureSize()
        capW = size.first
        capH = size.second
        cap.startCapture(capW, capH, FPS)
        val tr = f.createVideoTrack("screen0", src)
        tr.setEnabled(true)
        track = tr
        Logx.add("屏幕采集已开始：" + capW + "×" + capH + " @" + FPS + "fps，码率上限 " + cfg.maxKbps + " kbps")
    }

    private fun checkOrientation() {
        val size = captureSize()
        if (size.first != capW || size.second != capH) {
            capW = size.first
            capH = size.second
            try {
                capturer?.changeCaptureFormat(capW, capH, FPS)
                Logx.add("屏幕方向变化，采集尺寸改为 " + capW + "×" + capH)
                sendScr()
            } catch (e: Throwable) {
                Logx.add("调整采集尺寸失败: " + e.message)
            }
        }
    }

    private fun cleanupAll() {
        try { capturer?.stopCapture() } catch (e: Throwable) {}
        try { capturer?.dispose() } catch (e: Throwable) {}
        try { track?.dispose() } catch (e: Throwable) {}
        try { source?.dispose() } catch (e: Throwable) {}
        try { helper?.dispose() } catch (e: Throwable) {}
        try { factory?.dispose() } catch (e: Throwable) {}
        try { egl?.release() } catch (e: Throwable) {}
    }

    // ---------------------------------------------------------------- 中继配置

    private fun fetchIce(): List<PeerConnection.IceServer> {
        val out = ArrayList<PeerConnection.IceServer>()
        try {
            var url = cfg.base + "/ice-servers?resolve=1"
            if (cfg.auth.isNotEmpty()) url += "&auth=" + URLEncoder.encode(cfg.auth, "UTF-8")
            val j = JSONObject(Http.request("GET", url, null))
            val groups = j.optJSONArray("groups")
            val seen = HashSet<String>()
            var turnCount = 0
            if (groups != null) {
                for (gi in 0 until minOf(groups.length(), 2)) {
                    val g = groups.getJSONObject(gi)
                    val arr = g.optJSONArray("iceServers") ?: continue
                    for (i in 0 until arr.length()) {
                        val s = arr.getJSONObject(i)
                        val urls = ArrayList<String>()
                        val u = s.opt("urls")
                        if (u is JSONArray) {
                            for (k in 0 until u.length()) urls.add(u.getString(k))
                        } else if (u is String) {
                            urls.add(u)
                        }
                        if (urls.isEmpty()) continue
                        val key = urls.joinToString(",") + "|" + s.optString("username")
                        if (!seen.add(key)) continue
                        val b = PeerConnection.IceServer.builder(urls)
                        if (s.has("username")) b.setUsername(s.optString("username"))
                        if (s.has("credential")) b.setPassword(s.optString("credential"))
                        out.add(b.createIceServer())
                        for (x in urls) if (x.startsWith("turn")) turnCount++
                    }
                }
            }
            Logx.add("中继配置：" + out.size + " 组服务器，其中 TURN 地址 " + turnCount + " 个")
        } catch (e: Exception) {
            Logx.add("获取中继配置失败: " + e.message + "；改用公共 STUN（跨网络可能连不上）")
        }
        if (out.isEmpty()) {
            out.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }
        return out
    }

    // ---------------------------------------------------------------- SDP 同步小工具

    private fun createOffer(pc: PeerConnection): SessionDescription {
        val latch = CountDownLatch(1)
        var res: SessionDescription? = null
        var err = ""
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {
                res = d
                latch.countDown()
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String?) {
                err = e ?: ""
                latch.countDown()
            }
            override fun onSetFailure(e: String?) {}
        }, MediaConstraints())
        if (!latch.await(10, TimeUnit.SECONDS)) throw RuntimeException("创建 offer 超时")
        return res ?: throw RuntimeException("创建 offer 失败: " + err)
    }

    private fun setSdp(local: Boolean, pc: PeerConnection, d: SessionDescription) {
        val latch = CountDownLatch(1)
        var err: String? = null
        val o = object : SdpObserver {
            override fun onCreateSuccess(x: SessionDescription?) {}
            override fun onSetSuccess() {
                latch.countDown()
            }
            override fun onCreateFailure(e: String?) {}
            override fun onSetFailure(e: String?) {
                err = e ?: "未知错误"
                latch.countDown()
            }
        }
        if (local) pc.setLocalDescription(o, d) else pc.setRemoteDescription(o, d)
        if (!latch.await(10, TimeUnit.SECONDS)) throw RuntimeException("设置描述超时")
        val e = err
        if (e != null) throw RuntimeException("设置描述失败: " + e)
    }

    private fun tuneSender(sender: RtpSender) {
        try {
            val p = sender.parameters
            if (p.encodings.isNotEmpty()) {
                p.encodings[0].maxBitrateBps = cfg.maxKbps * 1000
                val ok = sender.setParameters(p)
                Logx.add("发送码率上限 " + cfg.maxKbps + " kbps：" + (if (ok) "已设置" else "设置失败"))
            }
        } catch (e: Throwable) {
            Logx.add("设置码率上限失败: " + e.message)
        }
    }

    // ---------------------------------------------------------------- 信令

    private fun putDoc(st: Sess) {
        st.dirty = false
        val arr = JSONArray()
        synchronized(st.cands) {
            for (i in 0 until st.cands.length()) arr.put(st.cands.get(i))
        }
        val doc = JSONObject()
            .put("ver", st.ver)
            .put("forCid", "")
            .put("offer", st.offer ?: JSONObject.NULL)
            .put("answer", JSONObject.NULL)
            .put("cands", arr)
            .put("relay", JSONObject.NULL)
        val body = JSONObject()
            .put("room", cfg.room)
            .put("cid", st.cid)
            .put("role", "o")
            .put("doc", doc)
        st.lastPutAt = System.currentTimeMillis()
        Http.request("POST", cfg.base + "/put", body.toString())
    }

    private fun applyRemoteCands(pc: PeerConnection, st: Sess, doc: JSONObject) {
        val arr = doc.optJSONArray("cands") ?: return
        while (st.peerCandCount < arr.length()) {
            val o = arr.getJSONObject(st.peerCandCount)
            st.peerCandCount++
            val s = o.optString("candidate")
            if (s.isEmpty()) continue
            val mid: String? = if (o.isNull("sdpMid")) null else o.optString("sdpMid")
            val idx = o.optInt("sdpMLineIndex", 0)
            try {
                pc.addIceCandidate(IceCandidate(mid, idx, s))
            } catch (e: Throwable) {
                Logx.add("添加对方候选失败: " + e.message)
            }
        }
    }

    private fun sendDc(dc: DataChannel?, msg: String) {
        try {
            if (dc != null && dc.state() == DataChannel.State.OPEN) {
                dc.send(DataChannel.Buffer(ByteBuffer.wrap(msg.toByteArray(Charsets.UTF_8)), false))
            }
        } catch (e: Throwable) {
        }
    }

    private fun logStats(pc: PeerConnection, st: Sess) {
        pc.getStats { report ->
            try {
                val m = report.statsMap
                var bytes = -1.0
                var w = 0
                var h = 0
                var fps = 0.0
                var limit = ""
                var rtt = -1.0
                var path = ""
                for (s in m.values) {
                    val mem = s.members
                    if (s.type == "outbound-rtp") {
                        val kind = (mem["kind"] ?: mem["mediaType"]) as? String
                        if (kind == "video") {
                            bytes = (mem["bytesSent"] as? Number)?.toDouble() ?: -1.0
                            w = (mem["frameWidth"] as? Number)?.toInt() ?: 0
                            h = (mem["frameHeight"] as? Number)?.toInt() ?: 0
                            fps = (mem["framesPerSecond"] as? Number)?.toDouble() ?: 0.0
                            limit = (mem["qualityLimitationReason"] as? String) ?: ""
                        }
                    } else if (s.type == "candidate-pair") {
                        if (mem["state"] == "succeeded" && mem["nominated"] == true) {
                            rtt = ((mem["currentRoundTripTime"] as? Number)?.toDouble() ?: -1.0) * 1000.0
                            val lt = (m[mem["localCandidateId"] as? String ?: ""]?.members?.get("candidateType")) as? String ?: "?"
                            val rt = (m[mem["remoteCandidateId"] as? String ?: ""]?.members?.get("candidateType")) as? String ?: "?"
                            path = lt + "↔" + rt
                        }
                    }
                }
                val now = System.currentTimeMillis()
                var mbps = -1.0
                if (bytes >= 0 && st.lastBytes >= 0 && now > st.lastBytesAt) {
                    mbps = (bytes - st.lastBytes) * 8.0 / ((now - st.lastBytesAt) / 1000.0) / 1e6
                }
                if (bytes >= 0) {
                    st.lastBytes = bytes
                    st.lastBytesAt = now
                }
                Logx.add(
                    "统计 | 发送 " + (if (mbps >= 0) String.format("%.2f", mbps) else "--") + " Mbps · " +
                        w + "×" + h + " · " + String.format("%.0f", fps) + "fps · RTT " +
                        (if (rtt >= 0) String.format("%.0f", rtt) + "ms" else "--") +
                        " · 路径 " + (if (path.isEmpty()) "--" else path) +
                        (if (limit.isNotEmpty() && limit != "none") " · 受限:" + limit else "")
                )
            } catch (e: Throwable) {
                Logx.add("统计读取失败: " + e.message)
            }
        }
    }

    // ---------------------------------------------------------------- 一次会话

    /** 返回 "peer-left" 表示观看者离开、可以重新等；其他字符串表示要结束共享。 */
    private fun runSession(): String {
        val st = Sess(UUID.randomUUID().toString().replace("-", "").substring(0, 16))
        var pc: PeerConnection? = null
        var chan: DataChannel? = null
        var joined = false
        try {
            val ice = fetchIce()

            val jr = JSONObject(
                Http.request(
                    "POST", cfg.base + "/join",
                    JSONObject().put("room", cfg.room).put("cid", st.cid).put("relay", JSONObject.NULL).toString()
                )
            )
            val role = jr.optString("role")
            if (role != "offerer") {
                return "房间「" + cfg.room + "」里已经有网页在等了。手机要先开始共享、网页后加入：请换一个房间名，或等 100 秒后重试"
            }
            joined = true
            Logx.add("已创建房间「" + cfg.room + "」，等待网页加入…")
            pairCode = ControlHandler.onSessionStart()

            val obs = object : PeerConnection.Observer {
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                    val name = if (s == null) "" else s.name
                    st.iceState = name
                    st.disconnectedAt = if (name == "DISCONNECTED") System.currentTimeMillis() else 0L
                    Logx.add("ICE 状态: " + name)
                }
                override fun onStandardizedIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
                override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {}
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                    if (s == PeerConnection.IceGatheringState.COMPLETE) st.gatherDone = true
                }
                override fun onIceCandidate(c: IceCandidate?) {
                    if (c == null) return
                    val o = JSONObject()
                        .put("candidate", c.sdp)
                        .put("sdpMid", c.sdpMid ?: JSONObject.NULL)
                        .put("sdpMLineIndex", c.sdpMLineIndex)
                        .put("usernameFragment", JSONObject.NULL)
                    synchronized(st.cands) { st.cands.put(o) }
                    if (c.sdp.contains(" typ relay")) st.hasRelay = true
                    st.dirty = true
                }
                override fun onIceCandidatesRemoved(a: Array<out IceCandidate>?) {}
                override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent?) {}
                override fun onAddStream(s: MediaStream?) {}
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onDataChannel(d: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(r: RtpReceiver?, a: Array<out MediaStream>?) {}
                override fun onTrack(transceiver: RtpTransceiver?) {}
                override fun onRemoveTrack(receiver: RtpReceiver?) {}
            }

            val rtcCfg = PeerConnection.RTCConfiguration(ice)
            rtcCfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            val p = factory!!.createPeerConnection(rtcCfg, obs) ?: return "创建连接失败"
            pc = p

            // 只发送屏幕画面，不接收对方的画面
            val sender = p.addTrack(track, listOf("screen"))
            for (t in p.transceivers) {
                if (t.sender.id() == sender.id()) {
                    t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                }
            }

            // 控制通道：和网页一样，两端各自创建 negotiated、id=0 的 "ctl"
            val init = DataChannel.Init()
            init.negotiated = true
            init.id = 0
            val ch: DataChannel = p.createDataChannel("ctl", init)
            chan = ch
            curChan = ch
            ch.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(prev: Long) {}
                override fun onStateChange() {
                    if (ch.state() == DataChannel.State.OPEN) {
                        st.dcOpen = true
                        st.lastRx = System.currentTimeMillis()
                        Logx.add("控制通道已打开")
                        sendDc(ch, "cam:1") // 告诉网页“有画面”，网页才会正常显示
                        sendScr()
                    }
                }
                override fun onMessage(b: DataChannel.Buffer?) {
                    if (b == null || b.binary) return
                    val bytes = ByteArray(b.data.remaining())
                    b.data.get(bytes)
                    val msg = String(bytes, Charsets.UTF_8)
                    st.lastRx = System.currentTimeMillis()
                    if (msg == "bye") st.peerBye = true
                    if (msg == "away") st.peerAway = true
                    if (msg.startsWith("rc:")) ControlHandler.onMessage(msg) { r -> sendDc(ch, r) }
                }
            })

            val offer = createOffer(p)
            setSdp(true, p, offer)
            tuneSender(sender)

            // 等候选收集（有中继候选后再等 1.2 秒就够，最多 4 秒）
            val t0 = System.currentTimeMillis()
            while (!closed && !st.gatherDone && System.currentTimeMillis() - t0 < 4000) {
                if (st.hasRelay && System.currentTimeMillis() - t0 >= 1200) break
                sleepMs(50)
            }
            val ld = p.localDescription
            st.offer = JSONObject().put("type", "offer").put("sdp", ld.description)
            putDoc(st)
            var candN = 0
            synchronized(st.cands) { candN = st.cands.length() }
            Logx.add("offer v1 已写入（候选 " + candN + " 个，" + (if (st.hasRelay) "有" else "没有") + "中继候选），等待网页加入")

            var lastPoll = 0L
            var lastPing = 0L
            var lastStats = 0L
            var lastSizeChk = 0L
            var errCount = 0
            var everConnected = false

            while (!closed) {
                val now = System.currentTimeMillis()

                if (st.peerBye) {
                    Logx.add("网页端已挂断")
                    return "peer-left"
                }
                if (st.peerAway) {
                    Logx.add("网页端正在刷新或关闭")
                    return "peer-left"
                }
                if (st.iceState == "CONNECTED" || st.iceState == "COMPLETED") everConnected = true
                if (st.iceState == "FAILED") {
                    Logx.add("连接失败（ICE failed），重新等待")
                    return "peer-left"
                }
                if (st.disconnectedAt > 0 && now - st.disconnectedAt > 30000) {
                    Logx.add("连接中断超过 30 秒，重新等待")
                    return "peer-left"
                }
                if (st.dcOpen && now - st.lastRx > 20000) {
                    Logx.add("网页端 20 秒没有响应，重新等待")
                    return "peer-left"
                }

                if (st.dcOpen && now - lastPing >= 2000) {
                    sendDc(ch, "p")
                    lastPing = now
                }

                if (now - lastSizeChk >= 1000) {
                    lastSizeChk = now
                    checkOrientation()
                }

                if (everConnected && now - lastStats >= 10000) {
                    lastStats = now
                    logStats(p, st)
                }

                try {
                    // 候选有新增 → 重新写一次（至少间隔 1.2 秒）；等待期间每 40 秒写一次当心跳，连上后每 5 分钟一次
                    val hb = if (st.answered) 300000L else 40000L
                    if ((st.dirty && now - st.lastPutAt >= 1200) || now - st.lastPutAt >= hb) {
                        putDoc(st)
                    }

                    val interval = if (st.answered) 4000L else 2500L
                    if (now - lastPoll >= interval) {
                        lastPoll = now
                        val resp = JSONObject(
                            Http.request("GET", cfg.base + "/poll/" + Uri.encode(cfg.room) + "?want=a", null)
                        )
                        val doc = resp.optJSONObject("doc")
                        if (doc != null) {
                            if (!st.answered) {
                                val ans = doc.optJSONObject("answer")
                                if (ans != null && doc.optString("forCid") == st.cid) {
                                    st.peerCid = doc.optString("cid")
                                    setSdp(false, p, SessionDescription(SessionDescription.Type.ANSWER, ans.optString("sdp")))
                                    st.answered = true
                                    Logx.add("收到网页的 answer，开始连接…")
                                    applyRemoteCands(p, st, doc)
                                }
                            } else if (doc.optString("cid") == st.peerCid) {
                                applyRemoteCands(p, st, doc)
                            }
                        }
                        errCount = 0
                    }
                } catch (e: Exception) {
                    errCount++
                    Logx.add("信令请求失败(" + errCount + "): " + e.message)
                    if (errCount >= 12) return "信令连续失败，已停止: " + e.message
                    sleepMs(1500)
                }

                sleepMs(200)
            }
            return finishReason ?: "已结束"
        } finally {
            ControlHandler.onSessionEnd()
            pairCode = ""
            try {
                if (closed && chan != null) {
                    sendDc(chan, "bye")
                    sleepMs(300)
                }
            } catch (e: Throwable) {
            }
            try { chan?.close() } catch (e: Throwable) {}
            try { chan?.dispose() } catch (e: Throwable) {}
            try { pc?.close() } catch (e: Throwable) {}
            try { pc?.dispose() } catch (e: Throwable) {}
            if (joined) {
                try {
                    Http.request(
                        "POST", cfg.base + "/leave",
                        JSONObject().put("room", cfg.room).put("cid", st.cid).put("role", "o").toString()
                    )
                } catch (e: Throwable) {
                }
            }
        }
    }
}
