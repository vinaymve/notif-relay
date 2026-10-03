package dev.notifrelay

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.edit

/** Pairing info scanned from the Mac's QR code: `notifrelay://pair?v=1&s=<server>&t=<topic>&k=<key>`. */
class Pairing(val server: String, val topic: String, val key: ByteArray) {
    val url: String get() = server.trimEnd('/') + "/" + topic

    companion object {
        private val topicPattern = Regex("^[-_A-Za-z0-9]{8,64}$")

        fun parse(text: String): Pairing? {
            val uri = Uri.parse(text.trim())
            if (uri.scheme != "notifrelay" || uri.host != "pair") return null
            val server = uri.getQueryParameter("s") ?: return null
            val topic = uri.getQueryParameter("t") ?: return null
            val key = uri.getQueryParameter("k")?.let(::decodeKey) ?: return null
            if (!server.startsWith("https://") && !server.startsWith("http://")) return null
            if (!topicPattern.matches(topic) || key.size != 32) return null
            return Pairing(server, topic, key)
        }

        fun decodeKey(s: String): ByteArray? = try {
            Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            null
        }

        fun encodeKey(key: ByteArray): String =
            Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}

/** App-private settings: the pairing and which packages to forward. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("relay", Context.MODE_PRIVATE)

    var pairing: Pairing?
        get() {
            val server = prefs.getString("server", null) ?: return null
            val topic = prefs.getString("topic", null) ?: return null
            val key = prefs.getString("key", null)?.let(Pairing::decodeKey) ?: return null
            return Pairing(server, topic, key)
        }
        set(value) = prefs.edit {
            putString("server", value?.server)
            putString("topic", value?.topic)
            putString("key", value?.key?.let(Pairing::encodeKey))
        }

    val enabledApps: Set<String>
        get() = prefs.getStringSet("apps", emptySet())!!.toSet()

    fun setEnabled(pkg: String, enabled: Boolean) {
        val apps = enabledApps.toMutableSet()
        if (enabled) apps.add(pkg) else apps.remove(pkg)
        prefs.edit { putStringSet("apps", apps) }
    }
}
