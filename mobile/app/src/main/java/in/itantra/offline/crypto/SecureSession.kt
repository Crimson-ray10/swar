package `in`.itantra.offline.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

private val domain = "LinC-offline-pairing-v1".toByteArray()
fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
private fun equal(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
private fun derive(secret: ByteArray, salt: ByteArray, label: String, size: Int): ByteArray =
    ByteArray(size).also { out -> HKDFBytesGenerator(SHA256Digest()).apply {
        init(HKDFParameters(secret, salt, label.toByteArray())); generateBytes(out, 0, out.size)
    } }

class Identity(seed: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)) {
    private val key = Ed25519PrivateKeyParameters(seed.copyOf(), 0)
    val publicKey: ByteArray get() = key.generatePublicKey().encoded
    val id: UUID get() = idFor(publicKey)
    fun exportSeed(): ByteArray = key.encoded
    fun sign(bytes: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, key); update(bytes, 0, bytes.size); generateSignature()
    }
    companion object {
        fun idFor(publicKey: ByteArray): UUID {
            require(publicKey.size == 32)
            val b = ByteBuffer.wrap(sha256(publicKey)); return UUID(b.long, b.long)
        }
        fun verify(key: ByteArray, message: ByteArray, signature: ByteArray): Boolean = Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(key, 0)); update(message, 0, message.size); verifySignature(signature)
        }
    }
}

/** Direction-separated AEAD keys. A fresh cipher belongs to exactly one handshake, across all radios. */
class SessionCipher(private val sendKey: ByteArray, private val receiveKey: ByteArray, private val binding: ByteArray) {
    private var sending = 0L
    private var highest = -1L
    private var seen = 0L
    @Synchronized fun seal(plaintext: ByteArray): ByteArray {
        require(plaintext.size <= 12000 && sending < Long.MAX_VALUE)
        val counter = sending++
        val header = ByteBuffer.allocate(8).putLong(counter).array()
        return header + crypt(true, sendKey, counter, header, plaintext)
    }
    @Synchronized fun open(frame: ByteArray): ByteArray {
        require(frame.size in 24..12024)
        val counter = ByteBuffer.wrap(frame).long
        require(counter >= 0)
        val distance = highest - counter
        require(distance < 64 && (distance < 0 || seen and (1L shl distance.toInt()) == 0L)) { "Replay or stale frame" }
        // Authentication must succeed before the replay window advances.
        val plaintext = crypt(false, receiveKey, counter, frame.copyOfRange(0, 8), frame.copyOfRange(8, frame.size))
        if (counter > highest) {
            val advance = counter - highest
            seen = if (advance >= 64) 1L else (seen shl advance.toInt()) or 1L
            highest = counter
        } else seen = seen or (1L shl distance.toInt())
        return plaintext
    }
    private fun crypt(encrypt: Boolean, key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, binding + header))
        val output = ByteArray(cipher.getOutputSize(body.size))
        val size = cipher.processBytes(body, 0, body.size, output, 0)
        val end = cipher.doFinal(output, size)
        return output.copyOf(size + end)
    }
}

/**
 * Signed ephemeral X25519 pairing with commit-before-reveal and bilateral user confirmation.
 * The identity and ephemeral transcript bind key derivation, SAS, and AEAD. Cryptographic
 * primitives come from Bouncy Castle; this application's composition still requires review.
 */
class Pairing(private val identity: Identity, private val now: () -> Long, private val trusted: (ByteArray) -> Boolean) {
    private val started = now()
    private val ephemeral = X25519PrivateKeyParameters(SecureRandom())
    private val nonce = ByteArray(32).also(SecureRandom()::nextBytes)
    private val unsigned = identity.publicKey + ephemeral.generatePublicKey().encoded + nonce
    private val reveal = unsigned + identity.sign(domain + unsigned)
    private var commitment: ByteArray? = null
    private var remoteReveal: ByteArray? = null
    private var cipher: SessionCipher? = null
    private var localConfirmed = false
    private var remoteConfirmed = false
    private var rejected = false
    private var awareKey: ByteArray? = null
    var sas: String? = null; private set
    val remoteKey: ByteArray? get() = remoteReveal?.copyOfRange(0, 32)
    val remoteId: UUID? get() = remoteKey?.let(Identity::idFor)
    val ready: Boolean get() = !rejected && localConfirmed && remoteConfirmed
    val known: Boolean get() = remoteKey?.let(trusted) == true
    fun begin(): ByteArray = byteArrayOf(0) + sha256(domain + reveal)
    fun receive(frame: ByteArray): ByteArray? {
        try {
            require(!rejected && now() - started < 120_000) { "Pairing expired" }
            require(frame.isNotEmpty())
            return when (frame[0].toInt()) {
                0 -> {
                    require(frame.size == 33 && commitment == null) { "Repeated commitment" }
                    commitment = frame.copyOfRange(1, frame.size)
                    byteArrayOf(1) + reveal
                }
                1 -> {
                    require(frame.size == 161 && commitment != null && remoteReveal == null)
                    val remote = frame.copyOfRange(1, frame.size)
                    require(equal(commitment!!, sha256(domain + remote))) { "Commitment mismatch" }
                    val key = remote.copyOfRange(0, 32)
                    require(!equal(key, identity.publicKey)) { "Reflected identity" }
                    require(Identity.verify(key, domain + remote.copyOfRange(0, 96), remote.copyOfRange(96, 160))) { "Invalid identity signature" }
                    val secret = ByteArray(32)
                    ephemeral.generateSecret(X25519PublicKeyParameters(remote, 32), secret, 0)
                    val first = identity.id.toString() < Identity.idFor(key).toString()
                    val transcript = sha256(domain + if (first) reveal + remote else remote + reveal)
                    val keys = derive(secret, transcript, "traffic", 64)
                    awareKey = derive(secret, transcript, "wifi-aware", 32)
                    val sasBytes = derive(secret, transcript, "verification", 4)
                    sas = ((ByteBuffer.wrap(sasBytes).int.toLong() and 0xffffffffL) % 1_000_000).toString().padStart(6, '0')
                    val low = keys.copyOfRange(0, 32); val high = keys.copyOfRange(32, 64)
                    cipher = SessionCipher(if (first) low else high, if (first) high else low, transcript)
                    secret.fill(0); keys.fill(0)
                    remoteReveal = remote
                    null
                }
                2 -> {
                    require(cipher != null && !remoteConfirmed)
                    require(equal(cipher!!.open(frame.copyOfRange(1, frame.size)), domain + "confirmed".toByteArray()))
                    remoteConfirmed = true
                    null
                }
                else -> error("Invalid pairing message")
            }
        } catch (e: Exception) { rejected = true; throw e }
    }
    fun confirm(): ByteArray {
        require(!rejected && now() - started < 120_000 && cipher != null && !localConfirmed)
        localConfirmed = true
        return byteArrayOf(2) + cipher!!.seal(domain + "confirmed".toByteArray())
    }
    fun reject() { rejected = true }
    fun awarePassphrase(): String { check(ready); return java.util.Base64.getEncoder().encodeToString(awareKey!!) }
    fun encrypt(bytes: ByteArray): ByteArray { check(ready); return byteArrayOf(3) + cipher!!.seal(bytes) }
    fun decrypt(frame: ByteArray): ByteArray { check(ready); require(frame.firstOrNull() == 3.toByte()); return cipher!!.open(frame.copyOfRange(1, frame.size)) }
}
