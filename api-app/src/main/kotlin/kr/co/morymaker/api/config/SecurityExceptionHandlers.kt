package kr.co.morymaker.api.config

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kr.co.morymaker.api.dto.ErrorBody
import kr.co.morymaker.api.dto.ErrorDetail
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

/**
 * JWT가 없거나 만료·서명불일치·issuer불일치로 인증 자체가 실패한 요청에 401을 반환한다
 * (필터 레벨 — `BearerTokenAuthenticationFilter`가 이 지점까지 오기 전에 걸러낸 요청).
 */
@Component
class RestAuthenticationEntryPoint(
    objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {

    // 메시지가 고정값이므로 초기화 시점에 한 번만 직렬화
    private val body = objectMapper.writeValueAsString(ErrorBody(ErrorDetail("UNAUTHENTICATED", "인증이 필요합니다")))

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(body)
    }
}

/**
 * URL 패턴 기반 인가 실패(필터 레벨)의 방어적 폴백 — 이번 범위에는 URL 패턴 인가 규칙이
 * `permitAll`/`authenticated` 뿐이라 실제로는 거의 타지 않는다. 인터셉터·서비스 레이어에서
 * throw하는 접근 거부(스코프 위반 등)는 디스패처 이후 실행이라 여기가 아니라
 * `GlobalExceptionHandler`(@RestControllerAdvice)가 처리한다.
 */
@Component
class RestAccessDeniedHandler(
    objectMapper: ObjectMapper,
) : AccessDeniedHandler {

    private val body = objectMapper.writeValueAsString(ErrorBody(ErrorDetail("ROLE_FORBIDDEN", "접근 권한이 없습니다")))

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(body)
    }
}
