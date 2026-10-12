package com.rtbishop.look4sat.core.data.lotw

import com.rtbishop.look4sat.core.domain.repository.LoTWOperationException
import com.rtbishop.look4sat.core.domain.repository.LoTWProblem
import java.security.InvalidKeyException
import java.security.Provider
import java.security.spec.InvalidKeySpecException
import java.security.spec.KeySpec
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.SecretKeyFactorySpi
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Android's bundled BC factory rejects empty passwords even when PBKDF2 is available. */
class EmptyPasswordProviderTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    @Suppress("DEPRECATION")
    private val androidStyleProvider=object:Provider("LoTWEmptyPasswordTest",1.0,"Test-only empty-password rejection") {
        init { put("SecretKeyFactory.PBKDF2WithHmacSHA256",RejectEmptyPasswordFactory::class.java.name) }
    }

    @Test fun providerReallyRejectsAnEmptyPassword() {
        assertThrows(IllegalArgumentException::class.java) {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256",androidStyleProvider).generateSecret(
                PBEKeySpec(charArrayOf(),byteArrayOf(1),1,256))
        }
    }

    @Test fun derivesEmptyPasswordKeyWhenAndroidProviderRejectsIt() {
        val expected="62384466264daadc4144018c6bd864648272b34da8980d31521ffcce92ae003b"
            .chunked(2).map {it.toInt(16).toByte()}.toByteArray()
        assertArrayEquals(expected,derivePkcs12Key("PBKDF2WithHmacSHA256",charArrayOf(),"salt".toByteArray(),2,256) {
            SecretKeyFactory.getInstance(it,androidStyleProvider)
        })
    }

    @Test fun emptyPasswordVectorsCoverEverySupportedPrfWithoutCallingTheFactory() {
        val vectors = mapOf(
            "SHA1" to "133a4ce837b4d2521ee2bf03e11c71ca794e079713b87cd23f360bdda2e87872",
            "SHA256" to "62384466264daadc4144018c6bd864648272b34da8980d31521ffcce92ae003b",
            "SHA384" to "a34ba6857eee44bd41b06d30e2521564ec2116a3010aad3080f35d0dde7b5c86",
            "SHA512" to "cc5eacbb057f2a5982fce67bf70f79fccf4adf285f5cbae1a5a1c5df012a630a"
        )
        for ((prf, hex) in vectors) {
            val expected = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertArrayEquals(expected, derivePkcs12Key("PBKDF2WithHmac$prf", charArrayOf(),
                "salt".toByteArray(), 2, 256) { error("Empty passwords must not reach a platform PBKDF2 factory") })
        }
    }

    @Test fun protectedPasswordsStillUseThePlatformFactory() {
        val actual = derivePkcs12Key("PBKDF2WithHmacSHA256", "password".toCharArray(), "salt".toByteArray(), 2, 256) {
            SecretKeyFactory.getInstance(it, androidStyleProvider)
        }
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256", "SunJCE")
            .generateSecret(PBEKeySpec("password".toCharArray(), "salt".toByteArray(), 2, 256)).encoded
        assertArrayEquals(expected, actual)
    }

    @Test fun importsUnprotectedCertificateWithPlatformFallback() {
        val material=LoTWKeyMaterial.read(fixture("test_tqsl_empty.p12"),charArrayOf(),System.currentTimeMillis()) {_,_->null}
        assertEquals("BA7OPF",material.info.callsign)
        assertEquals(318,material.info.dxcc)
    }

    @Test fun readsBothPlainAndEncryptedCertificateBagsWithEmptyPassword() {
        for(name in listOf("test_pbes2_empty.p12","test_pbes2_empty_enc.p12")) {
            val (key,certificate)=Pkcs12Reader.read(fixture(name),charArrayOf())
            assertEquals("RSA",key.algorithm)
            assertEquals("RSA",certificate.publicKey.algorithm)
        }
    }

    @Test fun blankInputOnProtectedCertificateStillReportsPasswordError() {
        val error=assertThrows(LoTWOperationException::class.java) {
            LoTWKeyMaterial.read(fixture("test_pbes2.p12"),charArrayOf(),System.currentTimeMillis()) {_,_->null}
        }
        assertEquals(LoTWProblem.CERTIFICATE_PASSWORD,error.reason)
    }

    class RejectEmptyPasswordFactory:SecretKeyFactorySpi() {
        override fun engineGenerateSecret(spec:KeySpec):SecretKey {
            require(spec is PBEKeySpec)
            if(spec.password.isEmpty())throw IllegalArgumentException("password empty")
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256","SunJCE").generateSecret(spec)
        }
        override fun engineGetKeySpec(key:SecretKey,spec:Class<*>):KeySpec=throw InvalidKeySpecException()
        override fun engineTranslateKey(key:SecretKey):SecretKey=throw InvalidKeyException()
    }
}
