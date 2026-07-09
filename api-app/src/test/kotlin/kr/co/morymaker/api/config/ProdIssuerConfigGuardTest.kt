package kr.co.morymaker.api.config

import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import kotlin.test.assertFailsWith

class ProdIssuerConfigGuardTest {

    @Test
    fun `issuer가 비어 있으면 기동 실패`() {
        val guard = ProdIssuerConfigGuard()
        ReflectionTestUtils.setField(guard, "issuer", "")
        assertFailsWith<IllegalStateException> { guard.validate() }
    }

    @Test
    fun `issuer가 https로 시작하지 않으면 기동 실패`() {
        val guard = ProdIssuerConfigGuard()
        ReflectionTestUtils.setField(guard, "issuer", "http://prod.morymaker.co.kr")
        assertFailsWith<IllegalStateException> { guard.validate() }
    }

    @Test
    fun `issuer가 https로 시작하면 통과`() {
        val guard = ProdIssuerConfigGuard()
        ReflectionTestUtils.setField(guard, "issuer", "https://auth.morymaker.co.kr")
        guard.validate()
    }
}
