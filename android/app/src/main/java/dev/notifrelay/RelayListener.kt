package dev.notifrelay

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android binds and keeps this service alive on its own once notification access is granted,
 * so there's no persistent connection or foreground service to babysit.
 */
class RelayListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Apps re-post the same notification often (progress, re-sorts); only forward real changes.
    private val lastSent = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 300
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val store = Store(this)
        val pairing = store.pairing ?: return
        if (sbn.packageName !in store.enabledApps) return
        if (sbn.isOngoing || isSummary(sbn.notification)) return

        val content = extract(this, sbn) ?: return
        val signature = (content.title + "\u0000" + content.text).hashCode()
        synchronized(lastSent) {
            if (lastSent[sbn.key] == signature) return
            lastSent[sbn.key] = signature
        }
        scope.launch { Relay.send(applicationContext, pairing, content) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /**
         * Roll-ups like WhatsApp's "New message from 1 chat" (title "[ ‎ ]"). Not every app sets
         * FLAG_GROUP_SUMMARY, so also catch grouped InboxStyle notifications and placeholder titles.
         */
        private fun isSummary(n: Notification): Boolean {
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return true
            val template = n.extras.getString(Notification.EXTRA_TEMPLATE)
            if (n.group != null && template == "android.app.Notification\$InboxStyle") return true
            val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            return title.isNotEmpty() && title.none { it.isLetterOrDigit() }
        }

        fun extract(context: Context, sbn: StatusBarNotification): Content? {
            val notification = sbn.notification
            val extras = notification.extras
            var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            var text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()

            // Chat apps (WhatsApp, Messages, Telegram…) keep a whole conversation in one
            // notification; forward just the newest message.
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)?.let { style ->
                val last = style.messages.lastOrNull() ?: return@let
                val sender = last.person?.name?.toString()
                    ?: return null // no sender = a message you sent yourself
                val conversation = style.conversationTitle?.toString()
                title = if (conversation != null && conversation != sender) "$sender · $conversation" else sender
                text = last.text?.toString() ?: text
            }

            if (title.isBlank() && text.isBlank()) return null
            return Content(appLabel(context, sbn.packageName), sbn.packageName, title, text)
        }

        private fun appLabel(context: Context, pkg: String): String = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }
}
