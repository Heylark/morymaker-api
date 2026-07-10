package kr.co.morymaker.api.application.port.`in`

/**
 * 대기화면 콘텐츠 키오스크 공개 조회 유스케이스 포트-in(§11-2, M3 — ADR-003) — api-app의
 * `PublicIdleContentController`가 호출한다.
 *
 * 무인증 공개 경로다 — `EventScopeGuard.assertAccess`를 호출하지 않는다([PublicOnsiteUseCase]·
 * `PublicHubUseCase`와 동일한 구조적 설계, 생성자에 EventScopeGuard 자체가 없다). 격리는
 * `event_id` WHERE 필터로만 제공한다 — 존재하지 않는 eid도 빈 배열(fail-open)로 응답한다.
 */
interface PublicIdleContentUseCase {

    /** 키오스크 대기화면 재생 목록(§11-2) — sortOrder 순, 존재하지 않는 eid는 빈 배열. */
    fun listForKiosk(eventId: String): List<IdleContentView>
}
