package joinbot

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyStatus
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmParameters
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.security.GeneralSecurityException

/** AES-256-GCM per ADR 0002; a weaker AEAD would satisfy Tink's parse but not the design. */
private const val MANDATED_AEAD_KEY_SIZE_BYTES = 32



/**
 * Tink AES-256-GCM AEAD for the sensitive columns. Rotation is free: retired keys stay in the
 * keyset, so old ciphertexts still [open].
 *
 * @param dataKeysetJson JSON Tink keyset of AES-256-GCM key(s).
 * @throws GeneralSecurityException if the keyset cannot be parsed or holds a non-AES-256-GCM key.
 */
class Crypto(dataKeysetJson: String) {
    private val aead: Aead

    init {
        AeadConfig.register()
        val dataHandle = parse(dataKeysetJson)
        requireAes256Gcm(dataHandle, "DATA_KEYSET")
        aead = dataHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    /**
     * Encrypts [plaintext] and authenticates it against [aad] (associated data) using AES-256-GCM.
     *
     * @param plaintext The plaintext string to encrypt.
     * @param aad Associated authenticated data bound to the ciphertext.
     * @return The encrypted and authenticated ciphertext as a byte array.
     */
    fun seal(plaintext: String, aad: String): ByteArray =
        aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), aad.toByteArray(Charsets.UTF_8))

    /**
     * Decrypts [ciphertext] and verifies its authenticity against [aad] (associated data) using AES-256-GCM.
     *
     * @param ciphertext The encrypted ciphertext bytes to decrypt.
     * @param aad Associated authenticated data that must match what was provided during encryption.
     * @return The decrypted plaintext string.
     * @throws GeneralSecurityException if decryption or authentication fails (e.g. wrong key, tampered data, or mismatched AAD).
     */
    fun open(ciphertext: ByteArray, aad: String): String =
        String(aead.decrypt(ciphertext, aad.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)

    private fun parse(json: String): KeysetHandle =
        TinkJsonProtoKeysetFormat.parseKeyset(json, InsecureSecretKeyAccess.get())

    /**
     * Tink only confirms a keyset can produce an [Aead] primitive, not that it uses the
     * algorithm this design mandates. A keyset holds several keys so retired ones can still
     * [open]/decrypt after rotation, so every ENABLED key is checked here, not just the primary
     * -- a weak non-primary key introduced by a rotation would still accept and decrypt data.
     * DISABLED/DESTROYED keys carry no cryptographic capability and are skipped.
     */
    private fun requireAes256Gcm(handle: KeysetHandle, keysetName: String) {
        for (i in 0 until handle.size()) {
            val entry = handle.getAt(i)
            if (entry.status != KeyStatus.ENABLED) continue
            val params = entry.key.parameters
            if (params !is AesGcmParameters || params.keySizeBytes != MANDATED_AEAD_KEY_SIZE_BYTES) {
                throw GeneralSecurityException("$keysetName must contain only AES-256-GCM keys")
            }
        }
    }

}

/**
 * Generates a keyset equivalent to `tinkey create-keyset`. Used by tests and `KeygenMain`.
 */
object KeysetGen {
    /**
     * Generates a fresh AES-256-GCM AEAD keyset serialized as a JSON string.
     *
     * @return The JSON-serialized keyset containing a new AES-256-GCM primary key.
     */
    fun aead(): String {
        AeadConfig.register()
        return serialize(KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM))
    }

    private fun serialize(handle: KeysetHandle): String =
        TinkJsonProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get())
}
