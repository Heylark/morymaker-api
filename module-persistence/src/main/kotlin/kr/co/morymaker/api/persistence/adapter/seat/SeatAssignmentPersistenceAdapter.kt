package kr.co.morymaker.api.persistence.adapter.seat

import kr.co.morymaker.api.application.port.out.SeatAssignmentPort
import kr.co.morymaker.api.application.port.out.SeatGroupCounts
import kr.co.morymaker.api.application.port.out.SeatSlotRow
import kr.co.morymaker.api.domain.seat.SeatAssignment
import kr.co.morymaker.api.persistence.mapper.SeatAssignmentMapper
import org.springframework.stereotype.Component

/**
 * [SeatAssignmentPort] 구현체 — MyBatis 매퍼 위임 + DB 언어(GuestGroupRow) → 포트 타입(Map) 번역
 * (ACL, mybatis-advanced.md §4).
 *
 * 헥사고날 레이어: Persistence(adapter). `internal`: application 계층은 [SeatAssignmentPort]
 * 인터페이스만 의존한다.
 */
@Component
internal class SeatAssignmentPersistenceAdapter(
    private val assignmentMapper: SeatAssignmentMapper,
) : SeatAssignmentPort {

    override fun countsByGroup(eventId: String): List<SeatGroupCounts> = assignmentMapper.countsByGroup(eventId)

    override fun findByGroup(eventId: String, seatGroupId: String, offset: Int?, limit: Int?): List<SeatSlotRow> =
        assignmentMapper.findByGroup(eventId, seatGroupId, offset, limit)

    override fun countByGroup(eventId: String, seatGroupId: String): Int =
        assignmentMapper.countByGroup(eventId, seatGroupId)

    override fun findGuestIdsByGroup(seatGroupId: String): List<String> = assignmentMapper.findGuestIdsByGroup(seatGroupId)

    override fun findGroupIdsByGuestIds(eventId: String, guestIds: List<String>): Map<String, String> {
        // foreach IN절은 빈 리스트면 문법 오류("IN ()") — 호출 전 가드(ParkingZonePersistenceAdapter.insertBatch와 동일 원칙).
        if (guestIds.isEmpty()) return emptyMap()
        return assignmentMapper.findGroupIdsByGuestIds(eventId, guestIds).associate { it.guestId to it.seatGroupId }
    }

    override fun deleteByGroup(eventId: String, seatGroupId: String) = assignmentMapper.deleteByGroup(eventId, seatGroupId)

    override fun insertBatch(rows: List<SeatAssignment>) {
        // foreach 배치 INSERT는 빈 리스트면 문법 오류(VALUES 절 없음) — 호출 전 가드.
        if (rows.isNotEmpty()) assignmentMapper.insertBatch(rows)
    }

    override fun deleteEmptySeats(seatGroupId: String) = assignmentMapper.deleteEmptySeats(seatGroupId)

    override fun updateOrdForGroup(seatGroupId: String, ord: Int) = assignmentMapper.updateOrdForGroup(seatGroupId, ord)

    override fun findMembersOrderedByGuestName(seatGroupId: String): List<SeatAssignment> =
        assignmentMapper.findMembersOrderedByGuestName(seatGroupId)

    override fun updateOrd(id: String, ord: Int) = assignmentMapper.updateOrd(id, ord)
}
