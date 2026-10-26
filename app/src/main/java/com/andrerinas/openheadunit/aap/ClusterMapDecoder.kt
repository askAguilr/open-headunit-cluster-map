package com.andrerinas.openheadunit.aap

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.andrerinas.openheadunit.utils.AppLog
import java.nio.ByteBuffer

/** AVC decoder exclusively for the map. All calls are confined to the ClusterMap worker. */
internal class ClusterMapDecoder(private val onPicture: () -> Unit, private val onError: () -> Unit) {
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var waitingForIdr = true
    private var shownPicture = false
    private var failed = false
    private val handler by lazy { Handler(Looper.myLooper()!!) }
    private val info = MediaCodec.BufferInfo()
    private val drainTick = object : Runnable {
        override fun run() {
            guarded { drain() }
            if (codec != null) handler.postDelayed(this, 10)
        }
    }

    fun setSurface(value: Surface?) {
        stopCodec()
        surface = value
        failed = false
        startCodec()
    }

    fun configure(bytes: ByteArray) {
        val nals = ClusterMapH264.nals(bytes)
        val newSps = nals.firstOrNull { it.type == 7 }?.bytes ?: sps
        val newPps = nals.firstOrNull { it.type == 8 }?.bytes ?: pps
        if (newSps == null || newPps == null) {
            sps = newSps
            pps = newPps
            return
        }
        if (sps?.contentEquals(newSps) == true && pps?.contentEquals(newPps) == true && codec != null) return
        stopCodec()
        sps = newSps
        pps = newPps
        failed = false
        startCodec()
    }

    private fun startCodec() {
        val output = surface?.takeIf { it.isValid } ?: return
        val sequence = sps ?: return
        val picture = pps ?: return
        guarded {
            val format = MediaFormat.createVideoFormat("video/avc", ClusterMapProtocol.WIDTH, ClusterMapProtocol.HEIGHT)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sequence))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(picture))
            val created = MediaCodec.createDecoderByType("video/avc")
            codec = created // Publish before configure so a failure also releases it.
            created.configure(format, output, null, 0)
            created.start()
            waitingForIdr = true
            shownPicture = false
            AppLog.i("ClusterMap: AVC decoder started (${created.name})")
            handler.post(drainTick)
        }
    }

    fun frame(bytes: ByteArray, timestampUs: Long) {
        if (failed || surface == null) return
        val nals = ClusterMapH264.nals(bytes)
        if (nals.any { it.type == 7 || it.type == 8 }) configure(bytes)
        val active = codec ?: return
        val isIdr = nals.any { it.type == 5 }
        if (waitingForIdr && !isIdr) return
        guarded {
            drain()
            val slot = active.dequeueInputBuffer(0)
            if (slot < 0) {
                waitingForIdr = true
                onError()
                return@guarded
            }
            val buffer = active.getInputBuffer(slot) ?: error("AVC input buffer unavailable")
            buffer.clear()
            if (buffer.remaining() < bytes.size) error("AVC input exceeds codec capacity")
            buffer.put(bytes)
            active.queueInputBuffer(slot, 0, bytes.size, timestampUs, 0)
            waitingForIdr = false
            drain()
        }
    }

    private fun drain() {
        val active = codec ?: return
        repeat(8) {
            when (val slot = active.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> AppLog.i("ClusterMap: format=${active.outputFormat}")
                else -> if (slot >= 0) {
                    active.releaseOutputBuffer(slot, true)
                    if (!shownPicture) {
                        shownPicture = true
                        AppLog.i("ClusterMap: first map frame rendered")
                        onPicture()
                    }
                }
            }
        }
    }

    private fun guarded(action: () -> Unit) {
        try { action() } catch (e: Exception) {
            AppLog.e("ClusterMap: AVC decoder failed", e)
            stopCodec()
            failed = true
            onError()
        }
    }

    private fun stopCodec() {
        handler.removeCallbacks(drainTick)
        val old = codec
        codec = null
        try { old?.stop() } catch (_: Exception) { }
        try { old?.release() } catch (_: Exception) { }
        waitingForIdr = true
        shownPicture = false
    }

    fun resetStream() {
        stopCodec()
        sps = null
        pps = null
        failed = false
    }

    fun close() {
        resetStream()
        surface = null
    }
}
