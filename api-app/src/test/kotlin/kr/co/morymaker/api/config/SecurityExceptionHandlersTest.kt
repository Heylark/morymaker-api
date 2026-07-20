package kr.co.morymaker.api.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecurityExceptionHandlersTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun `인증 실패 시 401과 UNAUTHENTICATED JSON을 반환한다`() {
        val entryPoint = RestAuthenticationEntryPoint(objectMapper)
        val response = MockHttpServletResponse()

        entryPoint.commence(MockHttpServletRequest(), response, BadCredentialsException("인증 실패"))

        assertEquals(401, response.status)
        assertEquals("application/json", response.contentType?.substringBefore(";"))
        assertTrue(response.contentAsString.contains("UNAUTHENTICATED"))
    }

    @Test
    fun `접근 거부 시 403과 ROLE_FORBIDDEN JSON을 반환한다`() {
        val handler = RestAccessDeniedHandler(objectMapper)
        val response = MockHttpServletResponse()

        handler.handle(MockHttpServletRequest(), response, AccessDeniedException("거부"))

        assertEquals(403, response.status)
        assertTrue(response.contentAsString.contains("ROLE_FORBIDDEN"))
    }
}
