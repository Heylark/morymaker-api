package kr.co.morymaker.api.config

import io.mockk.mockk
import kr.co.morymaker.api.application.port.out.SmsSenderPort
import kr.co.morymaker.api.security.SmsSenderStubAdapter
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class SmsSenderProdGuardTest {

    @Test
    fun `활성 빈이 스텁이면 기동 실패`() {
        val guard = SmsSenderProdGuard(SmsSenderStubAdapter())
        assertFailsWith<IllegalStateException> { guard.validate() }
    }

    @Test
    fun `활성 빈이 스텁이 아니면 통과`() {
        val guard = SmsSenderProdGuard(mockk<SmsSenderPort>())
        guard.validate()
    }
}
