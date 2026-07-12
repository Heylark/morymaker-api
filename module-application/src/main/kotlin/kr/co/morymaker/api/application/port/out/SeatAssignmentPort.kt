package kr.co.morymaker.api.application.port.out

import kr.co.morymaker.api.domain.seat.SeatAssignment

/**
 * 좌석 배정 영속 포트-out — module-persistence의 `SeatAssignmentPersistenceAdapter`가 구현한다.
 *
 * ACL 원칙(mybatis-advanced.md §4): 이 인터페이스는 도메인 언어로 명명하고, Adapter가 매퍼(DB
 * 언어)로 번역한다. `SeatGroupService`(§12-1 집계·M4 재해석)와 `SeatAssignmentService`(§12-4~5)
 * 양쪽이 공유한다.
 */
interface SeatAssignmentPort {

    /** 그룹별 슬롯수(전체 행수)·배정수(guest_id≠null) 집계(§12-1). */
    fun countsByGroup(eventId: String): List<SeatGroupCounts>

    /** 그룹 배정 조회(§12-4, ord 정렬) — guestName 조인 read model. offset·limit이 null이면 전량 반환. */
    fun findByGroup(eventId: String, seatGroupId: String, offset: Int?, limit: Int?): List<SeatSlotRow>

    /** 그룹 배정 총 건수(§12-4 page meta). */
    fun countByGroup(eventId: String, seatGroupId: String): Int

    /** 그룹 내 현재 배정 guestId 목록(§12-5 M3 동기화 — 교체 전 조회, 제거 대상 계산용). */
    fun findGuestIdsByGroup(seatGroupId: String): List<String>

    /** cross-group 배정 여부(§12-5 M1 assignedElsewhere 사전검사) — guestId → 소속 seatGroupId. */
    fun findGroupIdsByGuestIds(eventId: String, guestIds: List<String>): Map<String, String>

    /** 그룹 전체 배정 삭제(§12-5 M1 원자 교체 1단계). */
    fun deleteByGroup(eventId: String, seatGroupId: String)

    /** 신규 배정 세트 일괄 삽입(§12-5 M1 원자 교체 2단계). 빈 리스트는 무동작(호출 전 가드는 Adapter 책임). */
    fun insertBatch(rows: List<SeatAssignment>)

    /** M4 ON→OFF 1단계 — 빈 좌석(guest_id IS NULL) 행 삭제. eventId는 cross-event 격리 방어심층 스코핑용. */
    fun deleteEmptySeats(eventId: String, seatGroupId: String)

    /** M4 ON→OFF 2단계 — 남은 멤버 ord 일괄 갱신([SeatAssignment.ORD_UNNUMBERED]). eventId는 cross-event 격리 방어심층 스코핑용. */
    fun updateOrdForGroup(eventId: String, seatGroupId: String, ord: Int)

    /** M4 OFF→ON 1단계 — 멤버 안정 정렬(guest name 오름차순, tiebreak id) 조회. */
    fun findMembersOrderedByGuestName(seatGroupId: String): List<SeatAssignment>

    /** M4 OFF→ON 2단계 — 단건 ord 갱신(재채번 적용). eventId는 cross-event 격리 방어심층 스코핑용. */
    fun updateOrd(eventId: String, id: String, ord: Int)
}

/** [SeatAssignmentPort.countsByGroup] 결과 1건(§12-1 assignedCount·slotCount). */
data class SeatGroupCounts(val seatGroupId: String, val slotCount: Int, val assignedCount: Int)

/** [SeatAssignmentPort.findByGroup] 결과 1건 — guest 테이블 1:1 조인(fan-out 없음, UNIQUE(guest_id) 보장). */
data class SeatSlotRow(val id: String, val ord: Int, val guestId: String?, val guestName: String?)
