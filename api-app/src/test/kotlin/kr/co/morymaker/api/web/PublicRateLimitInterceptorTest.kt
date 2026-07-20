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

    // 프로덕션 컨테이너가 만드는 3요소를 그대로 재현한다 — 앞단이 붙이는 외부 경로(requestURI)와
    // 앱이 보는 경로(servletPath)가 다르다는 점이 이 인터셉터 판정의 핵심이라, 테스트가 그 차이를
    // 재현하지 않으면 판정식이 바뀌어도 눈치채지 못한다.
    private fun postRequest(ip: String) = MockHttpServletRequest("POST", "$CONTEXT_PATH/public/r/ev1").apply {
        contextPath = CONTEXT_PATH
        servletPath = "/public/r/ev1"
        remoteAddr = ip
    }

    private fun getRequest(ip: String) = MockHttpServletRequest("GET", "$CONTEXT_PATH/public/r/ev1").apply {
        contextPath = CONTEXT_PATH
        servletPath = "/public/r/ev1"
        remoteAddr = ip
    }

    private fun kioskGetRequest(ip: String, appPath: String = "/public/events/ev1/attendees") =
        MockHttpServletRequest("GET", "$CONTEXT_PATH$appPath").apply {
            contextPath = CONTEXT_PATH
            servletPath = appPath
            remoteAddr = ip
        }

    companion object {
        private const val CONTEXT_PATH = "/api"
    }

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
                kioskGetRequest("8.8.8.8", "/public/events/ev1/parking-search"),
                MockHttpServletResponse(),
                Any(),
            ),
        )
        assertFailsWith<RateLimitExceededException> {
            target.preHandle(
                kioskGetRequest("8.8.8.8", "/public/events/ev1/parking-search"),
                MockHttpServletResponse(),
                Any(),
            )
        }
    }

    @Test
    fun `kiosk POST 체크인 요청도 검사 대상이다(POST 분기 재사용)`() {
        val target = interceptor(limit = 1)
        val checkinRequest = { ip: String ->
            MockHttpServletRequest("POST", "$CONTEXT_PATH/public/events/ev1/checkin").apply {
                contextPath = CONTEXT_PATH
                servletPath = "/public/events/ev1/checkin"
                remoteAddr = ip
            }
        }
        assertTrue(target.preHandle(checkinRequest("9.9.9.9"), MockHttpServletResponse(), Any()))
        assertFailsWith<RateLimitExceededException> {
            target.preHandle(checkinRequest("9.9.9.9"), MockHttpServletResponse(), Any())
        }
    }
}
