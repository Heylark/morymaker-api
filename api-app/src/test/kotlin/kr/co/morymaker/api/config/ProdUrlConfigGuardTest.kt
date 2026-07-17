package kr.co.morymaker.api.config

import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import kotlin.test.assertFailsWith

class ProdUrlConfigGuardTest {

    @Test
    fun `event-base-url이 비어 있으면 기동 실패`() {
        val guard = guardWith(eventBaseUrl = "")
        assertFailsWith<IllegalStateException> { guard.validate() }
    }

    @Test
    fun `qr-base-url이 http로 시작하면 기동 실패`() {
        val guard = guardWith(qrBaseUrl = "http://localhost:3000/app")
        assertFailsWith<IllegalStateException> { guard.validate() }
    }

    @Test
    fun `park-scan-url이 기본값(dev)을 그대로 쓰면 기동 실패`() {
        val guard = guardWith(parkScanUrl = "http://localhost:3000/app/p")
        assertFailsWith<IllegalStateException> { guard.validate() }
    }

    @Test
    fun `media-base-url이 http로 시작하면 기동 실패`() {
        val guard = guardWith(mediaBaseUrl = "http://localhost:30100/api")
        assertFailsWith<IllegalStateException> { guard.validate() }
    }

    @Test
    fun `네 값 전부 https면 통과`() {
        val guard = guardWith(
            eventBaseUrl = "https://xxx.service.com/app",
            qrBaseUrl = "https://xxx.service.com/app",
            parkScanUrl = "https://xxx.service.com/app/p",
            mediaBaseUrl = "https://xxx.service.com/api",
        )
        guard.validate()
    }

    private fun guardWith(
        eventBaseUrl: String = "https://xxx.service.com/app",
        qrBaseUrl: String = "https://xxx.service.com/app",
        parkScanUrl: String = "https://xxx.service.com/app/p",
        mediaBaseUrl: String = "https://xxx.service.com/api",
    ): ProdUrlConfigGuard {
        val guard = ProdUrlConfigGuard()
        ReflectionTestUtils.setField(guard, "eventBaseUrl", eventBaseUrl)
        ReflectionTestUtils.setField(guard, "qrBaseUrl", qrBaseUrl)
        ReflectionTestUtils.setField(guard, "parkScanUrl", parkScanUrl)
        ReflectionTestUtils.setField(guard, "mediaBaseUrl", mediaBaseUrl)
        return guard
    }
}
