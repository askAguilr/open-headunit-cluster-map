package com.andrerinas.openheadunit.aap

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Common
import com.andrerinas.openheadunit.aap.protocol.proto.Input
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.aap.protocol.messages.MediaAck
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.atomic.AtomicInteger

/** Session-local cluster channel. No main-display focus, decoder, geometry or recovery state. */
internal class ClusterMapSession(
    private val send: (AapMessage) -> Unit,
    private val recordSession: (Int, Int) -> Unit,
) {
    private val thread = HandlerThread("ClusterMap").apply { start() }
    private val worker = Handler(thread.looper)
    private val pending = AtomicInteger()
    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var setup = false
    @Volatile private var sessionId = 0
    private var lastRecoveryMs = 0L
    private var target: Surface? = null
    private var status: ((String?) -> Unit)? = null
    private val decoder = ClusterMapDecoder(
        onPicture = { status?.invoke(null) },
        onError = {
            status?.invoke("Map decoder unavailable. Expand to return to Android Auto.")
            requestKeyframe()
        })

    /** Called on the reader after general reassembly, before the main control gateway. */
    fun process(message: AapMessage): Boolean {
        if (closed) return false
        if (message.channel != Channel.ID_CLUSTER_VIDEO && message.channel != Channel.ID_CLUSTER_INPUT) return false
        if (message.flags.toInt() and AapMessageFraming.FLAG_BIT_CONTROL != 0) return false
        if (message.channel == Channel.ID_CLUSTER_INPUT) {
            if (message.type != Input.MsgType.BINDINGREQUEST_VALUE) return false
            send(AapMessage(message.channel, Input.MsgType.BINDINGRESPONSE_VALUE,
                Input.BindingResponse.newBuilder().setStatus(Common.MessageStatus.STATUS_SUCCESS).build()))
            return true
        }
        when (message.type) {
            Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE -> {
                val request = message.parse(Media.MediaSetupRequest.newBuilder()).build()
                AppLog.i("ClusterMap: SETUP codec=${request.type}")
                if (request.type != Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP_VALUE) {
                    post { status?.invoke("Phone requested an unsupported secondary map codec.") }
                    return true
                }
                send(AapMessage(message.channel, Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE,
                    Media.Config.newBuilder().setStatus(Media.Config.ConfigStatus.STATUS_READY)
                        .setMaxUnacked(ClusterMapProtocol.MAX_UNACKED).addConfigurationIndices(0).build()))
                setup = true
                sendFocus(true)
            }
            Media.MsgType.MEDIA_MESSAGE_START_VALUE -> {
                val start = message.parse(Media.Start.newBuilder()).build()
                sessionId = start.sessionId
                recordSession(message.channel, start.sessionId)
                AppLog.i("ClusterMap: START session=${start.sessionId} config=${start.configurationIndex}")
                post { decoder.resetStream() }
            }
            Media.MsgType.MEDIA_MESSAGE_STOP_VALUE -> {
                AppLog.i("ClusterMap: STOP")
                post {
                    decoder.resetStream()
                    status?.invoke("Waiting for a secondary map from the phone…")
                }
            }
            Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST_VALUE -> {
                // This must never reach the main display's 'Exit Android Auto' handler.
                val request = message.parse(Media.VideoFocusRequestNotification.newBuilder()).build()
                AppLog.i("ClusterMap: focus request ${request.mode}")
                sendFocus(true)
            }
            0, 1 -> receiveVideo(message)
            else -> return message.type >= 0x8000
        }
        return true
    }

    fun processClusterFocus(): Boolean {
        if (closed) return false
        sendFocus(true)
        return true
    }

    private fun receiveVideo(message: AapMessage) {
        val creditSession = sessionId
        val needsAck = message.type == 0
        val offset = ClusterMapProtocol.payloadOffset(message.type, message.dataOffset, message.size)
        // Bound copied data separately from the main stream. The receive thread never waits for a codec.
        if (offset == null || message.size - offset > 2 * 1024 * 1024 || pending.incrementAndGet() > 12) {
            if (offset != null && message.size - offset <= 2 * 1024 * 1024) pending.decrementAndGet()
            if (needsAck) send(MediaAck(message.channel, creditSession))
            post { requestKeyframe() }
            return
        }
        val bytes = message.data.copyOfRange(offset, message.size)
        val timestamp = if (needsAck) ClusterMapProtocol.timestampUs(message.data, message.dataOffset, message.size) else 0L
        val posted = post {
            try {
                if (message.type == 1) decoder.configure(bytes)
                else decoder.frame(bytes, timestamp)
            } finally {
                pending.decrementAndGet()
                if (needsAck) send(MediaAck(Channel.ID_CLUSTER_VIDEO, creditSession))
            }
        }
        if (!posted) {
            pending.decrementAndGet()
            if (needsAck) send(MediaAck(message.channel, creditSession))
        }
    }

    fun attach(surface: Surface, onStatus: (String?) -> Unit): Boolean =
        post {
            target = surface
            status = onStatus
            status?.invoke("Waiting for a secondary map from the phone…")
            decoder.setSurface(surface)
            requestKeyframe()
        }

    /** Release the Surface only on the decoder worker, after all work that might use it. */
    fun detach(surface: Surface, afterRelease: () -> Unit = {}) = synchronized(lock) {
        // A closed session still serializes release behind codec cleanup. If quitSafely has
        // already stopped accepting work, that cleanup has finished and release is safe here.
        val accepted = worker.post {
            if (target === surface) {
                decoder.setSurface(null)
                target = null
                status = null
            }
            surface.release()
            afterRelease()
        }
        if (!accepted) {
            surface.release()
            afterRelease()
        }
    }

    fun afterDecoderWork(action: () -> Unit) = synchronized(lock) {
        if (!worker.post { action() }) action()
    }

    private fun sendFocus(projected: Boolean) {
        if (!setup || closed) return
        send(AapMessage(Channel.ID_CLUSTER_VIDEO, Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE,
            Media.VideoFocusNotification.newBuilder()
                .setMode(if (projected) Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED else Media.VideoFocusMode.VIDEO_FOCUS_NATIVE)
                .setUnsolicited(true).build()))
    }

    private fun requestKeyframe() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecoveryMs < 2000 || !setup) return
        lastRecoveryMs = now
        AppLog.i("ClusterMap: requesting independent map keyframe")
        sendFocus(false)
        worker.postDelayed({ sendFocus(true) }, 150)
    }

    private fun post(action: () -> Unit): Boolean = synchronized(lock) {
        if (closed) false else worker.post {
            try { action() } catch (e: Exception) { AppLog.e("ClusterMap: worker failed", e) }
        }
    }

    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        // Cleanup follows previously accepted frames and detach requests.
        worker.post {
            decoder.close()
            status?.invoke("Android Auto disconnected")
            status = null
            target = null
            thread.quitSafely()
        }
    }
}
