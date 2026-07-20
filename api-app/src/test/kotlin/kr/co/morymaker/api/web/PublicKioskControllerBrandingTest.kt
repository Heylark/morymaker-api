package kr.co.morymaker.api.web

import io.mockk.every
import io.mockk.mockk
import kr.co.morymaker.api.application.port.`in`.PublicKioskUseCase
import kr.co.morymaker.api.domain.event.Event
import org.junit.jupiter.api.Test
import org.springframework.http.CacheControl
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [PublicKioskController.getBranding] 단위 테스트 — 컨트롤러 메서드를 HTTP 계층 없이 직접
 * 호출해 DTO 매핑·캐시 헤더만 검증한다(공유 DB 미사용). cross-event 격리·fail-closed 상태코드는
 * [PublicKioskUseCase]가 이미 보장하는 영역이라 [PublicKioskServiceTest]가 커버하고, 실 컨테이너
 * 통합 검증은 Tester가 `PublicKioskControllerTest`(실 MariaDB)로 확인한다.
 */
class PublicKioskControllerBrandingTest {

    private val kioskUseCase = mockk<PublicKioskUseCase>()
    private val controller = PublicKioskController(kioskUseCase)

    private fun sampleEvent(pointColor: String? = "#111111", defaultIdleMode: String? = "branded") = Event(
        id = "ev1", name = "테스트 행사", eventDate = null, place = null, type = null,
        status = Event.STATUS_PREPARING, active = true, bgColor = null, pointColor = pointColor,
        titleColor = null, bodyColor = null, kv = null, defaultIdleMode = defaultIdleMode,
        smsPolicy = null, createdAt = Instant.now(),
    )

    @Test
    fun `getBranding은 pointColor·defaultIdleMode를 응답 바디로 반환한다`() {
        every { kioskUseCase.getBranding("ev1") } returns sampleEvent()

        val response = controller.getBranding("ev1")

        assertEquals(200, response.statusCode.value())
        assertEquals("#111111", response.body?.data?.pointColor)
        assertEquals("branded", response.body?.data?.defaultIdleMode)
    }

    @Test
    fun `getBranding은 미설정 이벤트의 null을 그대로 직렬화 대상으로 반환한다`() {
        every { kioskUseCase.getBranding("ev1") } returns sampleEvent(pointColor = null, defaultIdleMode = null)

        val response = controller.getBranding("ev1")

        assertNull(response.body?.data?.pointColor)
        assertNull(response.body?.data?.defaultIdleMode)
    }

    @Test
    fun `getBranding은 Cache-Control no-cache 헤더를 명시한다(immutable 복붙 방지)`() {
        every { kioskUseCase.getBranding("ev1") } returns sampleEvent()

        val response = controller.getBranding("ev1")

        assertEquals(CacheControl.noCache().headerValue, response.headers.cacheControl)
    }
}
