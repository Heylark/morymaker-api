package kr.co.morymaker.api.security

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kr.co.morymaker.api.application.security.EventScopeGuard
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.HandlerMapping
import kotlin.test.assertTrue

/**
 * [EventScopeInterceptor]가 `{eid}` 경로변수 존재 여부에 따라 [EventScopeGuard.assertAccess]를
 * 호출/생략하는지 검증한다 — 실제 스코프 판정 로직 자체는 [EventScopeGuardAdapterTest]가 담당.
 */
class EventScopeInterceptorTest {

    private val eventScopeGuard = mockk<EventScopeGuard>(relaxed = true)
    private val interceptor = EventScopeInterceptor(eventScopeGuard)
    private val response = mockk<HttpServletResponse>()

    @Test
    fun `eid 경로변수가 있으면 assertAccess를 호출한다`() {
        val request = mockk<HttpServletRequest>()
        every {
            request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)
        } returns mapOf("eid" to "ev1")

        val result = interceptor.preHandle(request, response, Any())

        assertTrue(result)
        verify(exactly = 1) { eventScopeGuard.assertAccess("ev1") }
    }

    @Test
    fun `eid 경로변수가 없으면(목록·생성) assertAccess를 호출하지 않는다`() {
        val request = mockk<HttpServletRequest>()
        every {
            request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)
        } returns emptyMap<String, String>()

        val result = interceptor.preHandle(request, response, Any())

        assertTrue(result)
        verify(exactly = 0) { eventScopeGuard.assertAccess(any()) }
    }

    @Test
    fun `핸들러 매핑 속성 자체가 없으면(null) assertAccess를 호출하지 않는다`() {
        val request = mockk<HttpServletRequest>()
        every {
            request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)
        } returns null

        val result = interceptor.preHandle(request, response, Any())

        assertTrue(result)
        verify(exactly = 0) { eventScopeGuard.assertAccess(any()) }
    }
}
