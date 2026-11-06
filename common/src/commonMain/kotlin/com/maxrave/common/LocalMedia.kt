package com.maxrave.common

/**
 * Identity of a track played from a file the user owns (Nexus Music fork, Phase 4a).
 *
 * The id carries the file's location itself: `local:` + the hex of its URI (a `content://` tree
 * document on Android, an absolute path on Desktop). Every path that rebuilds a playable item from
 * a bare id (queue restore, a song row, "play next" from a menu) can therefore recover the file
 * without a database lookup, and the players can tell a local item from a YouTube one by the id
 * alone.
 *
 * Hex on purpose: ids are matched with `contains(MERGING_DATA_TYPE.VIDEO)` in several places, and a
 * string of [0-9a-f] can never contain "Video". A raw URI or base64 could.
 */
object LocalMedia {
    const val ID_PREFIX = "local:"

    fun isLocal(id: String?): Boolean = id != null && id.startsWith(ID_PREFIX)

    fun idFor(uri: String): String = ID_PREFIX + uri.encodeToByteArray().toHex()

    /** The URI or path a local id was made from, or null if [id] is not a local id. */
    fun uriOf(id: String?): String? {
        if (!isLocal(id)) return null
        val hex = id!!.removePrefix(ID_PREFIX)
        if (hex.length % 2 != 0) return null
        return runCatching {
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }.decodeToString()
        }.getOrNull()
    }

    /**
     * Extensions scanned as audio. AIFF is listed but only Desktop can use it: Android has no
     * AIFF extractor (Media3) or retriever, so the Android scanner skips it.
     */
    val AUDIO_EXTENSIONS: Set<String> =
        setOf("flac", "m4a", "alac", "mp4", "wav", "wave", "aif", "aiff", "aifc", "mp3", "aac", "opus", "ogg", "oga")

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(digits[v ushr 4]).append(digits[v and 0x0F])
        }
        return out.toString()
    }
}
