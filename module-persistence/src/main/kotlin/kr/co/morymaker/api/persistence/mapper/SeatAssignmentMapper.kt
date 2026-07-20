package kr.co.morymaker.api.persistence.mapper

import kr.co.morymaker.api.application.port.out.SeatGroupCounts
import kr.co.morymaker.api.application.port.out.SeatSlotRow
import kr.co.morymaker.api.domain.seat.SeatAssignment
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * seat_assignment 테이블 MyBatis 매퍼 인터페이스(§12-4~6, 신설).
 *
 * 헥사고날 레이어: persistence(mapper) — DB 관심사만. 컬럼명 기반 명시 매핑(positional index
 * 금지 — GuestMapper·ParkingRecordMapper와 동일 원칙).
 *
 * XML 정의: resources/mapper/seat/SeatAssignmentMapper.xml
 */
@Mapper
interface SeatAssignmentMapper {

    /** 그룹별 슬롯수·배정수 집계(§12-1). */
    fun countsByGroup(@Param("eventId") eventId: String): List<SeatGroupCounts>

    /** 그룹 배정 조회(§12-4, ord 정렬 + guest 1:1 조인). offset·limit이 null이면 전량 반환. */
    fun findByGroup(
        @Param("eventId") eventId: String,
        @Param("seatGroupId") seatGroupId: String,
        @Param("offset") offset: Int?,
        @Param("limit") limit: Int?,
    ): List<SeatSlotRow>

    /** 그룹 배정 총 건수(§12-4 page meta). */
    fun countByGroup(@Param("eventId") eventId: String, @Param("seatGroupId") seatGroupId: String): Int

    /** 그룹 내 현재 배정 guestId 목록(§12-5 M3 동기화 — 교체 전 조회). */
    fun findGuestIdsByGroup(@Param("seatGroupId") seatGroupId: String): List<String>

    /** cross-group 배정 조회(§12-5 M1 assignedElsewhere 사전검사). */
    fun findGroupIdsByGuestIds(@Param("eventId") eventId: String, @Param("guestIds") guestIds: List<String>): List<GuestGroupRow>

    /** 그룹 전체 배정 삭제(§12-5 M1 원자 교체 1단계). */
    fun deleteByGroup(@Param("eventId") eventId: String, @Param("seatGroupId") seatGroupId: String)

    /** 신규 배정 세트 일괄 삽입(§12-5 M1 원자 교체 2단계). */
    fun insertBatch(@Param("list") rows: List<SeatAssignment>)

    /** M4 ON→OFF 1단계 — 빈 좌석(guest_id IS NULL) 행 삭제. eventId는 cross-event 격리 방어심층. */
    fun deleteEmptySeats(@Param("eventId") eventId: String, @Param("seatGroupId") seatGroupId: String)

    /** M4 ON→OFF 2단계 — 남은 멤버 ord 일괄 갱신. eventId는 cross-event 격리 방어심층. */
    fun updateOrdForGroup(@Param("eventId") eventId: String, @Param("seatGroupId") seatGroupId: String, @Param("ord") ord: Int)

    /** M4 OFF→ON 1단계 — 멤버 안정 정렬(guest name 오름차순, tiebreak id) 조회. */
    fun findMembersOrderedByGuestName(@Param("seatGroupId") seatGroupId: String): List<SeatAssignment>

    /** M4 OFF→ON 2단계 — 단건 ord 갱신. eventId는 cross-event 격리 방어심층. */
    fun updateOrd(@Param("eventId") eventId: String, @Param("id") id: String, @Param("ord") ord: Int)
}

/** [SeatAssignmentMapper.findGroupIdsByGuestIds] 조회 행 — Adapter가 Map으로 번역한다(ACL, mybatis-advanced.md §4). */
data class GuestGroupRow(val guestId: String, val seatGroupId: String)
