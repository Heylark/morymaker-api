package kr.co.morymaker.api.application.port.out

import kr.co.morymaker.api.domain.seat.SeatGroup

/**
 * 좌석 그룹 영속 포트-out — module-persistence의 `SeatGroupPersistenceAdapter`가 구현한다.
 *
 * ACL 원칙(mybatis-advanced.md §4): 이 인터페이스는 도메인 언어(eventId·gid·groupNo)로 명명하고,
 * Adapter가 매퍼(DB 언어)로 번역한다.
 */
interface SeatGroupPort {

    /** 목록(§12-1). */
    fun findByEvent(eventId: String): List<SeatGroup>

    /** 소유 검증 겸 단건 조회(§12-3). 없으면 null. */
    fun fetchById(eventId: String, id: String): SeatGroup?

    /** groupNo 기준 단건 조회(§12-4·§12-5 그룹 해석). 없으면 null. */
    fun fetchByGroupNo(eventId: String, groupNo: Int): SeatGroup?

    /** 신규 등록(§12-2) 채번 — 행사 내 현재 최대값+1. */
    fun nextGroupNo(eventId: String): Int

    /** 신규 등록(§12-2) 채번 — 행사 내 현재 최대값+1. */
    fun nextSortOrder(eventId: String): Int

    fun insert(group: SeatGroup)

    fun update(group: SeatGroup)

    /** 삭제(§12-3). `fk_seatassign_group`(CASCADE)·`fk_guest_seat_group`(SET NULL)가 종속 데이터를 자동 정리한다. */
    fun delete(eventId: String, id: String)
}
