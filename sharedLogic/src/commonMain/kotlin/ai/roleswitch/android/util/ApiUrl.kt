//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.util

/**
 * Whether [host] names a machine on the user's own network rather than somewhere on
 * the internet. This is what decides the default scheme and port in [normalizeApiUrl].
 *
 * Local: `localhost`, loopback (`127.x`, which covers the `10.0.2.2` alias an emulator
 * uses for its host through the `10.` range below), the RFC1918 private ranges,
 * link-local `169.254.x`, an mDNS `.local` name, and any single-label hostname — a bare
 * `raspberrypi` has nowhere to resolve except the local network.
 *
 * IPv6 literals are not recognized. The surrounding parser splits the port on the last
 * `:` and mangles them long before they reach here, which is pre-existing; this function
 * does not make it worse, because an unrecognized host is treated as remote and the
 * result is a default that is too strict rather than too loose.
 */
private fun isLocalHost(host: String): Boolean {
    val h = host.lowercase()
    if (h == "localhost" || h.endsWith(".local")) return true
    if (!h.contains('.')) return true
    if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")) return true
    if (h.startsWith("169.254.")) return true
    if (h.startsWith("172.")) {
        val second = h.removePrefix("172.").substringBefore('.').toIntOrNull()
        if (second != null && second in 16..31) return true
    }
    return false
}

/**
 * Normalizes a user-entered API URL into a form usable by the OpenAI-compatible client.
 *
 * An explicit `http://` or `https://` is always respected. Where none was typed, the
 * host decides: [isLocalHost] gets `http://` and LM Studio's default port `1234`,
 * because that is what a local server is, and anything else gets `https://` and no
 * invented port. `/v1` is appended when the URL has no path (or only a root `/`); any
 * existing non-root path is preserved verbatim.
 *
 * The local/remote split is the point, not a convenience. This function used to default
 * *everything* schemaless to `http://`, so typing a public hostname silently produced a
 * plaintext endpoint — and what crosses that connection is the chat itself: message
 * content, attached images, voice transcriptions, and the API token as a Bearer header.
 * Cleartext to a box on your own Wi-Fi is the feature; cleartext to the internet was
 * never asked for. Callers normalize on focus-loss and write the result back into the
 * visible field, so the scheme this picks is shown to the user rather than assumed
 * behind their back, and an explicit `http://` still overrides it.
 */
fun normalizeApiUrl(input: String): String {
    val raw = input.trim()
    if (raw.isEmpty()) return raw
    val hasSchema = raw.startsWith("http://") || raw.startsWith("https://")
    val rest = if (hasSchema) raw.substringAfter("://") else raw
    val slashIdx = rest.indexOf('/')
    val hostPort = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
    val path = if (slashIdx >= 0) rest.substring(slashIdx) else ""
    if (hostPort.isBlank()) return raw
    val colonIdx = hostPort.lastIndexOf(':')
    val hasPort = colonIdx >= 0
    val host = if (hasPort) hostPort.substring(0, colonIdx) else hostPort
    if (host.isBlank()) return raw
    val port = if (hasPort) hostPort.substring(colonIdx + 1) else null
    val local = isLocalHost(host)
    val schema = when {
        raw.startsWith("https://") -> "https://"
        raw.startsWith("http://") -> "http://"
        local -> "http://"
        else -> "https://"
    }
    val effectivePort = when {
        hasPort -> port
        !hasSchema && local -> "1234"
        else -> null
    }
    val normalizedPath = when {
        path.isBlank() || path == "/" -> "/v1"
        else -> path
    }
    val portSuffix = effectivePort?.let { ":$it" } ?: ""
    return "$schema$host$portSuffix$normalizedPath"
}

/**
 * The host of a user-entered API URL, for naming the destination in the Data Sharing
 * notice ("…transmit your messages to **192.168.0.100**"). Returns null when the input
 * has no host to show yet, so the caller can fall back to generic wording.
 *
 * Deliberately tolerant of half-typed input — the notice updates as the user types, and
 * a URL is only normalized once the field loses focus. Parsing is the same string walk
 * [normalizeApiUrl] does rather than a platform URL type, so Android and iOS name the
 * same host for the same input.
 */
fun hostnameFromApiUrl(input: String): String? {
    val raw = input.trim()
    if (raw.isEmpty()) return null
    val rest = if (raw.contains("://")) raw.substringAfter("://") else raw
    val slashIdx = rest.indexOf('/')
    val hostPort = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
    if (hostPort.isBlank()) return null
    val colonIdx = hostPort.lastIndexOf(':')
    val host = if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
    return host.ifBlank { null }
}
