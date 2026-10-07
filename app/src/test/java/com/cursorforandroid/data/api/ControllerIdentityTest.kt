package com.cursorforandroid.data.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** RFC 7638 thumbprint, the desktop verification code, and the label the pairing prompt shows. */
class ControllerIdentityTest {

    @Test
    fun `the RFC 7638 P-256 example hashes to the documented thumbprint`() {
        val stored = ControllerIdentity.Stored(
            kty = "EC",
            crv = "P-256",
            x = "MKBCTNIcKUSDii11ySs3526iDZ8AiTo7Tu6KPAqv7D4",
            y = "4Etl6SRW2YiLUrN5vfvVHuhp7x8PxltmWWlbbM4IFyM",
            d = "870MB6gfuTJ4HtUnUvYMyJpr5eUZNP4Bk43bVdj3eAE",
            clientInstanceId = "id",
        )
        assertThat(ControllerIdentity.thumbprint(stored)).isEqualTo("cn-I_WNMClehiVp51i_0VpOENW1upEerA8sEam5hn-s")
        assertThat(ControllerIdentity.verificationCode(ControllerIdentity.thumbprint(stored))).isEqualTo("2096-0765")
        assertThat(ControllerIdentity.publicJson(stored)).isEqualTo(
            """{"kty":"EC","crv":"P-256","x":"MKBCTNIcKUSDii11ySs3526iDZ8AiTo7Tu6KPAqv7D4","y":"4Etl6SRW2YiLUrN5vfvVHuhp7x8PxltmWWlbbM4IFyM"}""",
        )
    }

    @Test
    fun `the verification code is eight digits with a hyphen, from the first four thumbprint bytes`() {
        val code = ControllerIdentity.verificationCode("cn-I_WNMClehiVp51i_0VpOENW1upEerA8sEam5hn-s")
        assertThat(code).matches("""\d{4}-\d{4}""")
        assertThat(ControllerIdentity.verificationCode("!!!")).isEqualTo("0000-0000")
    }

    @Test
    fun `a generated key is P-256 with a public JWK that omits the private coordinate`() {
        val stored = ControllerIdentity.generate()
        assertThat(stored.kty).isEqualTo("EC")
        assertThat(stored.crv).isEqualTo("P-256")
        assertThat(stored.x).isNotEmpty()
        assertThat(stored.y).isNotEmpty()
        assertThat(stored.d).isNotEmpty()
        assertThat(stored.clientInstanceId).isNotEmpty()
        val publicJson = ControllerIdentity.publicJson(stored)
        assertThat(publicJson).doesNotContain("\"d\"")
        assertThat(ControllerIdentity.thumbprint(stored)).isNotEmpty()
        assertThat(ControllerIdentity.verificationCode(ControllerIdentity.thumbprint(stored))).matches("""\d{4}-\d{4}""")
    }

    @Test
    fun `the pairing label is a short clean device name`() {
        assertThat(ControllerIdentity.sanitizeLabel("")).isEqualTo("Android")
        assertThat(ControllerIdentity.sanitizeLabel("  Pixel   9  ")).isEqualTo("Pixel 9")
        assertThat(ControllerIdentity.sanitizeLabel("Pixel[9]")).isEqualTo("Pixel9")
        val long = "A".repeat(80)
        val cleaned = ControllerIdentity.sanitizeLabel(long)
        assertThat(cleaned.length).isEqualTo(64)
        assertThat(cleaned).endsWith("…")
    }
}
