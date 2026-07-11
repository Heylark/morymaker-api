package kr.co.morymaker.api.web

import kr.co.morymaker.api.config.PublicProperties
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [PublicRateLimitInterceptor] 단위 테스트 — 인메모리 고정윈도 임계 판정·GET 미검사·IP별
 * 독립 카운팅을 검증한다.
 */
class PublicRateLimitInterceptorTest {

    private fun interceptor(limit: Int = 3, windowSeconds: Int = 60) = PublicRateLimitInterceptor(
        PublicProperties(
            eventBaseUrl = "http://localhost:3000",
            parkScanUrl = "http://localhost:3000/p",
            rateLimit = PublicProperties.RateLimit(limit = limit, windowSeconds = windowSeconds),
        ),
    )

    private fun postRequest(ip: String) = MockHttpServletRequest("POST", "/api/public/r/ev1").apply { remoteAddr = ip }
    private fun getRequest(ip: String) = MockHttpServletRequest("GET", "/api/public/r/ev1").apply { remoteAddr = ip }
    private fun kioskGetRequest(ip: String, path: String = "/api/public/events/ev1/attendees") =
        MockHttpServletRequest("GET", path).apply { remoteAddr = ip }

    @Test
    fun `한도 이내 요청은 통과한다`() {
        val target = interceptor(limit = 3)
        repeat(3) {
            assertTrue(target.preHandle(postRequest("1.1.1.1"), MockHttpServletResponse(), Any()))
        }
    }

    @Test
    fun `한도를 초과하면 RateLimitExceededException을 던진다`() {
        val target = interceptor(limit = 2)
        target.preHandle(postRequest("2.2.2.2"), MockHttpServletResponse(), Any())
        target.preHandle(postRequest("2.2.2.2"), MockHttpServletResponse(), Any())

        assertFailsWith<RateLimitExceededException> {
            target.preHandle(postRequest("2.2.2.2"), MockHttpServletResponse(), Any())
        }
    }

    @Test
    fun `현장등록 GET 요청은 검사 대상이 아니다(한도 초과해도 통과, byte-identical 보존)`() {
        val target = interceptor(limit = 1)
        repeat(5) {
            assertTrue(target.preHandle(getRequest("3.3.3.3"), MockHttpServletResponse(), Any()))
        }
    }

    @Test
    fun `IP별로 독립 카운팅된다`() {
        val target = interceptor(limit = 1)
        assertTrue(target.preHandle(postRequest("4.4.4.4"), MockHttpServletResponse(), Any()))
        // 다른 IP는 별도 윈도를 가지므로 여기서 429가 나면 안 된다.
        assertTrue(target.preHandle(postRequest("5.5.5.5"), MockHttpServletResponse(), Any()))
    }

    // ── kiosk GET 검사 대상 확장 ──────────────────────────────────────

    @Test
    fun `kiosk 이름검색 GET 요청은 한도 이내면 통과한다`() {
        val target = interceptor(limit = 3)
        repeat(3) {
            assertTrue(target.preHandle(kioskGetRequest("6.6.6.6"), MockHttpServletResponse(), Any()))
        }
    }

    @Test
    fun `kiosk 이름검색 GET 요청은 한도를 초과하면 RateLimitExceededException을 던진다`() {
        val target = interceptor(limit = 2)
        target.preHandle(kioskGetRequest("7.7.7.7"), MockHttpServletResponse(), Any())
        target.preHandle(kioskGetRequest("7.7.7.7"), MockHttpServletResponse(), Any())

        assertFailsWith<RateLimitExceededException> {
            target.preHandle(kioskGetRequest("7.7.7.7"), MockHttpServletResponse(), Any())
        }
    }

    @Test
    fun `kiosk 주차검색 GET 요청도 검사 대상이다`() {
        val target = interceptor(limit = 1)
        assertTrue(
            target.preHandle(
                kioskGetRequest("8.8.8.8", "/api/public/events/ev1/parking-search"),
                MockHttpServletResponse(),
                Any(),
            ),
        )
        assertFailsWith<RateLimitExceededException> {
            target.preHandle(
                kioskGetRequest("8.8.8.8", "/api/public/events/ev1/parking-search"),
                MockHttpServletResponse(),
                Any(),
            )
        }
    }

    @Test
    fun `kiosk POST 체크인 요청도 검사 대상이다(POST 분기 재사용)`() {
        val target = interceptor(limit = 1)
        val checkinRequest = { ip: String ->
            MockHttpServletRequest("POST", "/api/public/events/ev1/checkin").apply { remoteAddr = ip }
        }
        assertTrue(target.preHandle(checkinRequest("9.9.9.9"), MockHttpServletResponse(), Any()))
        assertFailsWith<RateLimitExceededException> {
            target.preHandle(checkinRequest("9.9.9.9"), MockHttpServletResponse(), Any())
        }
    }
}
