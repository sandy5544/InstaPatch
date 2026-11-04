package app.auralis.player.addons

data class ResolvedAudio(
    val url: String, val mimeType: String?, val codec: String?, val bitrate: Int?,
    val sampleRate: Int?, val bitDepth: Int?, val sourceName: String
) {
    init { require(url.startsWith("https://")) { "Only HTTPS media streams are accepted" } }
}
enum class RequestedQuality { AUTO, HIGH, LOSSLESS, HI_RES }
