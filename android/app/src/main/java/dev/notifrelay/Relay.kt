package dev.notifrelay

import android.content.Context
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class Content(val app: String, val pkg: String, val title: String, val text: String)

/**
 * Encrypts a notification and POSTs it to the ntfy topic. Tries immediately; if that
 * fails (offline, captive portal, etc.) hands it to WorkManager to retry once there's a network.
 */
object Relay {
    // ntfy turns bodies over 4096 bytes into attachments; base64 adds ~33% plus 28 bytes of nonce+tag.
    private const val MAX_PLAINTEXT_BYTES = 2800
    private val random = SecureRandom()

    enum class Result { SENT, QUEUED, REJECTED }

    suspend fun send(context: Context, pairing: Pairing, content: Content): Result {
        val body = seal(payload(content), pairing.key)
        return when (post(pairing.url, body)) {
            PostResult.OK -> Result.SENT
            PostResult.PERMANENT_FAILURE -> Result.REJECTED
            PostResult.RETRY -> {
                enqueue(context, pairing.url, body)
                Result.QUEUED
            }
        }
    }

    /** AES-256-GCM, output base64(nonce[12] || ciphertext || tag[16]). Matches CryptoKit's `combined`. */
    fun seal(plain: ByteArray, key: ByteArray): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return Base64.encodeToString(nonce + cipher.doFinal(plain), Base64.NO_WRAP)
    }

    private fun payload(c: Content): ByteArray {
        var limit = 2000
        while (true) {
            val text = if (c.text.length > limit) c.text.take(limit) + "…" else c.text
            val bytes = JSONObject()
                .put("v", 1)
                .put("app", c.app)
                .put("pkg", c.pkg)
                .put("title", c.title.take(200))
                .put("text", text)
                .put("ts", System.currentTimeMillis())
                .toString()
                .toByteArray()
            if (bytes.size <= MAX_PLAINTEXT_BYTES || limit == 0) return bytes
            limit /= 2
        }
    }

    enum class PostResult { OK, RETRY, PERMANENT_FAILURE }

    suspend fun post(url: String, body: String): PostResult = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Content-Type", "text/plain")
                // Don't forward to ntfy's Firebase (only useful for the ntfy Android app).
                conn.setRequestProperty("X-Firebase", "no")
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                when {
                    code in 200..299 -> PostResult.OK
                    code == 429 || code >= 500 -> PostResult.RETRY
                    else -> PostResult.PERMANENT_FAILURE
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            PostResult.RETRY
        }
    }

    private fun enqueue(context: Context, url: String, body: String) {
        val request = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(workDataOf("url" to url, "body" to body))
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}

class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val url = inputData.getString("url") ?: return Result.failure()
        val body = inputData.getString("body") ?: return Result.failure()
        // A notification that's an hour late isn't worth much; give up after a handful of tries.
        if (runAttemptCount >= 6) return Result.failure()
        return when (Relay.post(url, body)) {
            Relay.PostResult.OK -> Result.success()
            Relay.PostResult.RETRY -> Result.retry()
            Relay.PostResult.PERMANENT_FAILURE -> Result.failure()
        }
    }
}
