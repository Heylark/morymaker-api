package kr.co.morymaker.api.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kr.co.morymaker.api.application.security.EventScopeGuard
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping

/**
 * 행사 스코프 게이트 Layer2a(1차 방어, 자동적용) — `{eid}` 경로변수가 있는 요청을
 * 컨트롤러 도달 전에 가로채 [EventScopeGuard.assertAccess]를 호출한다.
 *
 * `WebMvcConfig`가 `/events` 이하 전체 경로에 이 인터셉터를 등록하므로, 신규 `{eid}`
 * 엔드포인트가 애노테이션 없이도 자동으로 방어된다 — "메서드마다 `@PreAuthorize` 하드코딩 →
 * 깜빡함" 위험을 구조적으로 차단하는 것이 핵심 설계 의도다. `preHandle`은 핸들러(컨트롤러)
 * 실행보다 항상 먼저 실행되므로, DB 조회가 시작되기 전에 접근 여부가 확정된다(enumeration 방지).
 */
@Component
class EventScopeInterceptor(
    private val eventScopeGuard: EventScopeGuard,
) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        val pathVariables = request.getAttribute(
            HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
        ) as? Map<String, String>
        val eid = pathVariables?.get("eid")
        // {eid}가 없는 경로(목록·생성)는 자동 skip — 결과 필터링 또는 역할게이트만으로 처리된다.
        if (eid != null) {
            eventScopeGuard.assertAccess(eid)
        }
        return true
    }
}
