package com.andrerinas.openheadunit.aap

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.decoder.video.preflight.AnnexBAccessUnits
import com.google.protobuf.Message
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/** Android-only smoke fixture; uses a synthetic clip, never a phone or network connection. */
class ClusterMapPreviewActivity : Activity() {
    internal lateinit var map: ClusterMapPipView
    internal lateinit var session: ClusterMapSession
    internal val sent = CopyOnWriteArrayList<AapMessage>()
    private val main = Handler(Looper.getMainLooper())
    private var frame = 0
    private lateinit var clip: ByteArray
    private lateinit var units: List<AnnexBAccessUnits.Unit>
    private val feed = object : Runnable {
        override fun run() {
            val unit = units[frame % units.size]
            val picture = clip.copyOfRange(unit.offset, unit.offset + unit.length)
            val bytes = ByteBuffer.allocate(10 + picture.size).putShort(0).putLong(frame * 33333L).put(picture).array()
            session.process(AapMessage(Channel.ID_CLUSTER_VIDEO, 11, 0, 2, bytes.size, bytes))
            frame++
            main.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ClusterMapSession({ sent.add(it) }, { _, _ -> })
        map = ClusterMapPipView(this)
        setContentView(map)
        map.showMap(session)
        clip = resources.openRawResource(R.raw.preflight_h264_480p).use { it.readBytes() }
        units = AnnexBAccessUnits.split(clip, VideoDecoder.CodecType.H264)
        deliver(Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE,
            Media.MediaSetupRequest.newBuilder().setType(3).build())
        deliver(Media.MsgType.MEDIA_MESSAGE_START_VALUE,
            Media.Start.newBuilder().setSessionId(42).setConfigurationIndex(0).build())
        val first = units.first()
        val prefix = AnnexBAccessUnits.parameterSetPrefix(clip, first, VideoDecoder.CodecType.H264)
        val config = byteArrayOf(0, 1) + clip.copyOfRange(first.offset, first.offset + prefix)
        session.process(AapMessage(Channel.ID_CLUSTER_VIDEO, 11, 1, 2, config.size, config))
        main.postDelayed(feed, 200)
    }

    private fun deliver(type: Int, proto: Message) {
        val bytes = ByteBuffer.allocate(2 + proto.serializedSize).putShort(type.toShort()).put(proto.toByteArray()).array()
        session.process(AapMessage(Channel.ID_CLUSTER_VIDEO, 11, type, 2, bytes.size, bytes))
    }

    internal fun pip() = enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(5, 3)).build())

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        if (inPip) map.showMap(session)
    }

    override fun onDestroy() {
        main.removeCallbacks(feed)
        map.hideMap()
        session.close()
        super.onDestroy()
    }
}
