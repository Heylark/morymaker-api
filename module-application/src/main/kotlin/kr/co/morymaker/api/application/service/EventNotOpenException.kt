package kr.co.morymaker.api.application.service

/**
 * 현장등록 status 게이트(§10-6, 종료된 행사 거부) — `PublicOnsiteService`가 던지고, api-app의
 * `GlobalExceptionHandler`가 409(`EVENT_CLOSED`)로 변환한다.
 *
 * 준비·운영중 상태는 이 예외 대상이 아니다(종료만 거부) — 준비 단계의 행사 등록·테스트를
 * 막지 않기 위함이다.
 */
class EventNotOpenException(message: String) : RuntimeException(message)
