package net.markandrew.kiosk.music.receiver

import java.net.URI

/** Explicit endpoint only: never scan, infer or substitute a server. */
data class ReceiverEndpoint(val address: String, val path: String) {
    companion object {
        fun parse(value: String): ReceiverEndpoint {
            val uri = URI(value.trim())
            require(uri.scheme == "ws") { "Use ws://host:port/sendspin; Sendspin encrypts its protocol with Noise." }
            require(!uri.host.isNullOrBlank() && uri.port in 1..65535)
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            require(uri.rawPath == "/sendspin") { "Use the Sendspin endpoint, not the Music Assistant web page." }
            val host = uri.host.removePrefix("[").removeSuffix("]")
            val authority = if (host.contains(':')) "[$host]" else host
            return ReceiverEndpoint("$authority:${uri.port}", uri.rawPath)
        }
    }
}
