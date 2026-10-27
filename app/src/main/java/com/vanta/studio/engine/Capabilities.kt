package com.vanta.studio.engine

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/** What this device's H.264 hardware/software encoders can do, used to disable impossible export options. */
object Capabilities {
    private val encoders: List<MediaCodecInfo> by lazy {
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** True if some H.264 encoder reports support for this frame size at this rate. */
    fun supportsAvc(width: Int, height: Int, fps: Int): Boolean {
        if (encoders.isEmpty()) return true // unknown: let the exporter try (it has a fallback)
        return encoders.any { info ->
            try {
                val v = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities ?: return@any false
                // Encoders may only list one orientation; accept either.
                v.areSizeAndRateSupported(width, height, fps.toDouble()) ||
                    v.areSizeAndRateSupported(height, width, fps.toDouble())
            } catch (e: Exception) {
                false
            }
        }
    }
}
