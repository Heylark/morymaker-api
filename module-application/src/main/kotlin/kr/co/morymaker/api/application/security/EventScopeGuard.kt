package kr.co.morymaker.api.application.security

/**
 * 행사 스코프 게이트 포트 — 호출자가 특정 행사에 접근 가능한지 판정한다.
 *
 * 헥사고날 레이어: application(port). 구현체는 api-app의 `EventScopeGuardAdapter`가
 * SecurityContext의 JWT 클레임을 읽어 제공한다(애플리케이션 레이어는 JWT/web 프레임워크에
 * 오염되지 않는다).
 *
 * 두 메서드 모두 fail-CLOSED — SYSTEM_ADMIN 역할이 아니면서 스코프를 판정할 수 없는 경우
 * (인증 정보 없음, 클레임 손상 등) 항상 거부 쪽으로 수렴한다.
 */
interface EventScopeGuard {

    /**
     * 단건 접근 게이트(Layer2b) — `eid`가 호출자의 허용 범위 밖이면 [EventAccessDeniedException]을 던진다.
     * SYSTEM_ADMIN 역할이면 전체 허용. 그 외에는 JWT `event_ids` 클레임에 `eid`가 포함돼야 통과한다.
     */
    fun assertAccess(eid: String)

    /**
     * 목록 조회 결과 필터링용 현재 스코프 — assertAccess처럼 예외를 던지지 않고 스코프 자체를 반환한다.
     *
     * @return null(SYSTEM_ADMIN — 전체 허용, 필터링 불요) 또는 허용된 event_id 목록(빈 리스트 허용 —
     *         아직 배정된 행사가 없는 계정은 빈 목록으로 정상 조회된다)
     */
    fun currentScopeOrNull(): List<String>?
}
