package kr.co.morymaker.api.application.port.out

import kr.co.morymaker.api.domain.event.Event

/**
 * 행사 영속 포트-out — module-persistence의 `EventPersistenceAdapter`가 구현한다.
 */
interface EventPort {

    /** 단건 조회. 없으면 null. */
    fun fetch(id: String): Event?

    /**
     * 목록 조회.
     *
     * @param eventIds null이면 전체 조회(SYSTEM_ADMIN), 값이 있으면 해당 id 목록으로만 필터링
     *                 (빈 리스트면 결과도 빈 리스트 — 아직 담당 행사가 없는 계정).
     */
    fun search(eventIds: List<String>?): List<Event>

    /** 신규 행사 저장. */
    fun insert(event: Event)

    /** 일반 필드 갱신(§2-4) — name/event_date/place/type/kv/status/active만 SET한다(컬러·defaultIdleMode 미포함). */
    fun update(event: Event)

    /** 브랜딩 갱신(§11-1) — bg/point/title/body_color·kv·default_idle_mode만 SET한다. */
    fun updateBranding(event: Event)
}
