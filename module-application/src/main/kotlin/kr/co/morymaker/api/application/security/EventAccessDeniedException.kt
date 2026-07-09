package kr.co.morymaker.api.application.security

import org.springframework.security.access.AccessDeniedException

/**
 * 행사 스코프 위반 예외(EVENT_FORBIDDEN) — [EventScopeGuard] 구현체와 서비스 레이어(Layer2b
 * 재검증)가 던진다.
 *
 * 인터셉터·서비스 레이어에서 던져지는 이 예외는 Spring Security 필터 레벨 `AccessDeniedHandler`가
 * 잡지 못한다(디스패처 이후 실행이라 필터 체인을 이미 통과한 뒤이기 때문) — api-app의
 * `GlobalExceptionHandler`(@RestControllerAdvice)가 유일한 트랩이다.
 *
 * @param eid 거부된 행사 id — 메시지에는 포함하지 않는다(CWE-532, 로그·응답 노출 최소화 원칙).
 */
class EventAccessDeniedException(eid: String) : AccessDeniedException("담당 행사가 아닙니다")
