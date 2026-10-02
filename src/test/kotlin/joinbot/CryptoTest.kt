package joinbot

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.security.GeneralSecurityException

/** AES-128-GCM: valid for Tink, weaker than the mandated AES-256-GCM. */
private fun aes128Keyset(): String {
    AeadConfig.register()
    return TinkJsonProtoKeysetFormat.serializeKeyset(
        KeysetHandle.generateNew(PredefinedAeadParameters.AES128_GCM), InsecureSecretKeyAccess.get(),
    )
}

class CryptoTest : StringSpec({
    "round trip" { testCrypto().run { open(seal("hi", "t|1"), "t|1") shouldBe "hi" } }
    "aad mismatch fails" {
        testCrypto().run { shouldThrow<GeneralSecurityException> { open(seal("hi", "t|1"), "t|2") } }
    }
    "non-AES-256-GCM keyset rejected" { shouldThrow<GeneralSecurityException> { Crypto(aes128Keyset()) } }
})
