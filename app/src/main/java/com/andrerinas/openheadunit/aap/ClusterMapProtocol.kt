package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media

/** Secondary display description, independent of the main screen's geometry and codec. */
internal object ClusterMapProtocol {
    const val WIDTH = 800
    const val HEIGHT = 480
    const val MAX_UNACKED = 4

    fun videoService(): Control.Service = Control.Service.newBuilder()
        .setId(Channel.ID_CLUSTER_VIDEO)
        .setMediaSinkService(Control.Service.MediaSinkService.newBuilder()
            .setAvailableType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
            .setAvailableWhileInCall(true)
            .setDisplayId(1)
            .setDisplayType(1)
            .addVideoConfigs(Control.Service.MediaSinkService.VideoConfiguration.newBuilder()
                .setCodecResolution(Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480)
                .setFrameRate(Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30)
                .setMarginWidth(0).setMarginHeight(0).setDensity(160)
                .setDecoderAdditionalDepth(0).setViewingDistance(500)
                .setPixelAspectRatioE4(10000).setRealDensity(160)
                .setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                .setUiConfig(Media.UiConfig.newBuilder()
                    .setMargins(Media.Insets.newBuilder().setTop(0).setBottom(0).setLeft(0).setRight(0))
                    .setContentInsets(Media.Insets.getDefaultInstance())
                    .setStableContentInsets(Media.Insets.getDefaultInstance())
                    .setUiTheme(0))))
        .build()

    fun inputService(): Control.Service = Control.Service.newBuilder()
        .setId(Channel.ID_CLUSTER_INPUT)
        .setInputSourceService(Control.Service.InputSourceService.newBuilder()
            .setDisplayId(1).addKeycodesSupported(23).addKeycodesSupported(65536))
        .build()

    /** DATA has an eight-byte timestamp; CODEC_CONFIG starts directly with Annex B. */
    fun payloadOffset(type: Int, dataOffset: Int, size: Int): Int? {
        if (type !in 0..1 || dataOffset < 0 || dataOffset > size) return null
        val offset = dataOffset + if (type == 0) 8 else 0
        return offset.takeIf { it < size }
    }

    fun shouldAutoEnter(sessionActive: Boolean, finishing: Boolean, pipActive: Boolean, followsTouch: Boolean): Boolean =
        sessionActive && !finishing && !pipActive && !followsTouch

    fun timestampUs(data: ByteArray, dataOffset: Int, size: Int): Long {
        if (dataOffset < 0 || size > data.size || size - dataOffset < 8) return 0
        var value = 0L
        for (i in dataOffset until dataOffset + 8) value = (value shl 8) or (data[i].toLong() and 255)
        return value.coerceAtLeast(0L)
    }
}
