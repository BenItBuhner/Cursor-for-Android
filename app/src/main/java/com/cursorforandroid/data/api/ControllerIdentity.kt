package com.cursorforandroid.data.api

import com.cursorforandroid.data.local.SecureKeyStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/**
 * This phone as a Remote Control **controller**: a P-256 key stored in [SecureKeyStore], the RFC 7638 JWK thumbprint
 * `ListSharedTargets` wants, and the verification code the desktop shows (`pGi` in Cursor 3.23.23: first four bytes
 * of the thumbprint, as base64url-decoded SHA-256, taken as an unsigned int modulo 1e8, `XXXX-XXXX`).
 *
 * The phone never signs `ApproveController` — that JWT is the **computer's** DPoP. This identity is only
 * `TrustController.public_key_jwk` plus the thumbprint column on the computer list.
 */
class ControllerIdentity(
    private val keyStore: SecureKeyStore,
    /** Shown on the desktop pairing prompt (`TrustController.label`); the device model, truncated. */
    private val deviceLabel: String,
) {
    @Serializable
    data class Stored(
        val kty: String,
        val crv: String,
        val x: String,
        val y: String,
        val d: String,
        val clientInstanceId: String,
    )

    data class Public(
        val publicKeyJwk: String,
        val thumbprint: String,
        val verificationCode: String,
        val clientInstanceId: String,
        val label: String,
    )

    fun loadOrCreate(): Public {
        val stored = read() ?: generate().also { write(it) }
        val publicJwk = publicJson(stored)
        val thumbprint = thumbprint(stored)
        return Public(
            publicKeyJwk = publicJwk,
            thumbprint = thumbprint,
            verificationCode = verificationCode(thumbprint),
            clientInstanceId = stored.clientInstanceId,
            label = sanitizeLabel(deviceLabel),
        )
    }

    private fun read(): Stored? {
        val raw = keyStore.controllerKeyJson() ?: return null
        return runCatching { STORE_JSON.decodeFromString(Stored.serializer(), raw) }.getOrNull()?.takeIf { it.valid }
    }

    private fun write(stored: Stored): Boolean =
        keyStore.setControllerKeyJson(STORE_JSON.encodeToString(Stored.serializer(), stored))

    private val Stored.valid: Boolean
        get() = kty == "EC" && crv == "P-256" && x.isNotBlank() && y.isNotBlank() && d.isNotBlank() &&
            clientInstanceId.isNotBlank()

    companion object {
        private val STORE_JSON = Json { ignoreUnknownKeys = true }
        private val URL_B64 = Base64.getUrlEncoder().withoutPadding()
        private val URL_DEC = Base64.getUrlDecoder()
        private const val COORD_BYTES = 32
        private const val LABEL_MAX = 64

        fun generate(): Stored {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val pair = kpg.generateKeyPair()
            val pub = pair.public as ECPublicKey
            val priv = pair.private as ECPrivateKey
            val w = pub.w
            return Stored(
                kty = "EC",
                crv = "P-256",
                x = URL_B64.encodeToString(unsigned(w.affineX)),
                y = URL_B64.encodeToString(unsigned(w.affineY)),
                d = URL_B64.encodeToString(unsigned(priv.s)),
                clientInstanceId = UUID.randomUUID().toString(),
            )
        }

        /** Compact public JWK, the object Cursor's desktop `JSON.stringify`s for `publicKeyJwk`. */
        fun publicJson(stored: Stored): String =
            """{"kty":"${stored.kty}","crv":"${stored.crv}","x":"${stored.x}","y":"${stored.y}"}"""

        /**
         * RFC 7638 JWK thumbprint: SHA-256 of the lexicographic JSON `{"crv","kty","x","y"}`, then base64url
         * without padding.
         */
        fun thumbprint(stored: Stored): String {
            val canonical = """{"crv":"${stored.crv}","kty":"${stored.kty}","x":"${stored.x}","y":"${stored.y}"}"""
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return URL_B64.encodeToString(digest)
        }

        /** Desktop `IVE`/`pGi`: four-byte big-endian of the decoded thumbprint, `% 1e8`, `XXXX-XXXX`. */
        fun verificationCode(thumbprint: String): String {
            val bytes = runCatching { URL_DEC.decode(thumbprint) }.getOrDefault(ByteArray(0))
            val b0 = bytes.getOrElse(0) { 0 }.toInt() and 0xFF
            val b1 = bytes.getOrElse(1) { 0 }.toInt() and 0xFF
            val b2 = bytes.getOrElse(2) { 0 }.toInt() and 0xFF
            val b3 = bytes.getOrElse(3) { 0 }.toInt() and 0xFF
            val n = ((b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3).toLong() and 0xFFFFFFFFL
            val digits = (n % 100_000_000L).toString().padStart(8, '0')
            return "${digits.substring(0, 4)}-${digits.substring(4)}"
        }

        fun sanitizeLabel(raw: String): String {
            val cleaned = raw.replace(Regex("[\\[\\]\\p{Cc}\\p{Cf}]"), "").replace(Regex("\\s+"), " ").trim()
            return when {
                cleaned.isEmpty() -> "Android"
                cleaned.length <= LABEL_MAX -> cleaned
                else -> cleaned.take(LABEL_MAX - 1) + "…"
            }
        }

        private fun unsigned(value: BigInteger): ByteArray {
            val raw = value.toByteArray()
            val start = if (raw.isNotEmpty() && raw[0] == 0.toByte()) 1 else 0
            val length = raw.size - start
            return when {
                length == COORD_BYTES -> raw.copyOfRange(start, raw.size)
                length < COORD_BYTES -> ByteArray(COORD_BYTES - length) + raw.copyOfRange(start, raw.size)
                else -> raw.copyOfRange(raw.size - COORD_BYTES, raw.size)
            }
        }
    }
}
