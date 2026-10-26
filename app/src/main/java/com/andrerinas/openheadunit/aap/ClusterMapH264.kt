package com.andrerinas.openheadunit.aap

/** Minimal Annex B inspection. Video decoding stays with Android's AVC decoder. */
internal object ClusterMapH264 {
    data class Nal(val type: Int, val bytes: ByteArray)

    fun nals(bytes: ByteArray): List<Nal> {
        val starts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i + 3 < bytes.size) {
            val prefix = when {
                bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte() -> 3
                i + 4 < bytes.size && bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
                    bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (prefix > 0) {
                starts.add(i to prefix)
                i += prefix
            } else i++
        }
        return starts.mapIndexed { index, (start, prefix) ->
            val end = starts.getOrNull(index + 1)?.first ?: bytes.size
            Nal(bytes[start + prefix].toInt() and 31, bytes.copyOfRange(start, end))
        }
    }
}
