package `in`.itantra.offline.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only the private identity seed is encrypted; peer public keys are not secrets. */
class KnownPeersStore(context: Context) {
    private val prefs = context.getSharedPreferences("offline-identities", Context.MODE_PRIVATE)
    private val alias = "linc-identity-wrap-v1"
    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun identity(): Identity {
        val encoded = prefs.getString("seed", null)
        if (encoded != null) {
            val stored = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, stored.copyOfRange(0, 12)))
            return Identity(cipher.doFinal(stored.copyOfRange(12, stored.size)))
        }
        val identity = Identity()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val seed = identity.exportSeed()
        val stored = cipher.iv + cipher.doFinal(seed); seed.fill(0)
        check(prefs.edit().putString("seed", Base64.encodeToString(stored, Base64.NO_WRAP)).commit())
        return identity
    }
    fun trusted(key: ByteArray) = prefs.getString("peer-${Identity.idFor(key)}", null) == Base64.encodeToString(key, Base64.NO_WRAP)
    fun trust(key: ByteArray) { check(prefs.edit().putString("peer-${Identity.idFor(key)}", Base64.encodeToString(key, Base64.NO_WRAP)).commit()) }
    fun forget(key: ByteArray) { prefs.edit().remove("peer-${Identity.idFor(key)}").apply() }
}
