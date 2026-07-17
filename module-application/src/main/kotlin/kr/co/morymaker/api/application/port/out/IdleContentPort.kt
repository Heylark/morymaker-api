package kr.co.morymaker.api.application.port.out

import kr.co.morymaker.api.domain.idle.IdleContent

/**
 * 대기화면 콘텐츠 영속 포트-out — module-persistence의 `IdleContentPersistenceAdapter`가 구현한다.
 */
interface IdleContentPort {

    /** 목록 조회(§11-2) — sortOrder 순. 관리자·키오스크 양쪽에서 공용으로 사용한다. */
    fun findByEvent(eventId: String): List<IdleContent>

    /** 소유 검증 겸 단건 조회(§11-4). 없으면 null. */
    fun fetchById(eventId: String, id: String): IdleContent?

    /** 신규 콘텐츠 저장. */
    fun insert(content: IdleContent)

    /** 수정(§11-4) — mode/play/sort_order만 갱신. */
    fun update(content: IdleContent)

    /** 삭제(§11-4) — event_id 스코프 봉인(cross-event 방어심층). */
    fun delete(eventId: String, id: String)
}
