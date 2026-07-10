package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.SeatGroupCreateCommand
import kr.co.morymaker.api.application.port.`in`.SeatGroupUpdateCommand
import kr.co.morymaker.api.application.port.`in`.SeatGroupUseCase
import kr.co.morymaker.api.application.port.`in`.SeatGroupView
import kr.co.morymaker.api.application.port.out.SeatAssignmentPort
import kr.co.morymaker.api.application.port.out.SeatGroupCounts
import kr.co.morymaker.api.application.port.out.SeatGroupPort
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.seat.SeatAssignment
import kr.co.morymaker.api.domain.seat.SeatGroup
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [SeatGroupUseCase] 구현체 — 그룹 CRUD(§12-1~3)와 numbering 토글 재해석(M4, `NUMBERING-TOGGLE-ORD`)을
 * 담당한다.
 *
 * 헥사고날 레이어: application(service). `internal`: api-app은 [SeatGroupUseCase] 인터페이스만
 * 의존한다. `SeatAssignmentPort`에 단방향 의존(집계·M4 재해석) — Parking의
 * `ParkingZoneService`→`ParkingSlotTitlePort` 결합과 동일 형태다.
 */
@Service
internal class SeatGroupService(
    private val groupPort: SeatGroupPort,
    private val assignmentPort: SeatAssignmentPort,
    private val eventScopeGuard: EventScopeGuard,
) : SeatGroupUseCase {

    @Transactional(readOnly = true)
    override fun listGroups(eventId: String): List<SeatGroupView> {
        eventScopeGuard.assertAccess(eventId)
        val groups = groupPort.findByEvent(eventId)
        val countsByGroupId = assignmentPort.countsByGroup(eventId).associateBy { it.seatGroupId }
        return groups.map { it.toView(countsByGroupId[it.id]) }
    }

    @Transactional
    override fun createGroup(eventId: String, command: SeatGroupCreateCommand): SeatGroupView {
        eventScopeGuard.assertAccess(eventId)
        val group = SeatGroup(
            id = UUID.randomUUID().toString(),
            eventId = eventId,
            groupNo = groupPort.nextGroupNo(eventId),
            label = command.label,
            numbering = command.numbering,
            sortOrder = groupPort.nextSortOrder(eventId),
        )
        groupPort.insert(group)
        // 신규 그룹은 배정이 있을 수 없다(등록 시점에 seat_assignment 행을 만들지 않는다 — §12-5에서 관리자가 추가).
        return group.toView(null)
    }

    @Transactional
    override fun updateGroup(eventId: String, gid: String, command: SeatGroupUpdateCommand): SeatGroupView {
        eventScopeGuard.assertAccess(eventId)
        val existing = groupPort.fetchById(eventId, gid) ?: throw NoSuchElementException("좌석 그룹을 찾을 수 없습니다")
        val merged = existing.with(label = command.label, numbering = command.numbering)
        groupPort.update(merged)

        // M4 — numbering 값이 실제로 바뀔 때만 재해석(라벨만 수정은 no-op).
        if (existing.numbering != command.numbering) {
            if (command.numbering) reorderOffToOn(gid) else reorderOnToOff(gid)
        }

        val counts = assignmentPort.countsByGroup(eventId).firstOrNull { it.seatGroupId == gid }
        return merged.toView(counts)
    }

    @Transactional
    override fun deleteGroup(eventId: String, gid: String) {
        eventScopeGuard.assertAccess(eventId)
        groupPort.fetchById(eventId, gid) ?: throw NoSuchElementException("좌석 그룹을 찾을 수 없습니다")
        // fk_seatassign_group(ON DELETE CASCADE)가 seat_assignment 행을 동반 삭제하고,
        // fk_guest_seat_group(ON DELETE SET NULL)이 guest.seat_group_id를 자동으로 비운다(V1 기존
        // 제약 — 애플리케이션에서 별도로 동기화할 필요가 없다).
        groupPort.delete(eventId, gid)
    }

    // ON→OFF: 빈 좌석(guest_id IS NULL) 삭제 + 남은 멤버 ord=ORD_UNNUMBERED 일괄 갱신.
    private fun reorderOnToOff(seatGroupId: String) {
        assignmentPort.deleteEmptySeats(seatGroupId)
        assignmentPort.updateOrdForGroup(seatGroupId, SeatAssignment.ORD_UNNUMBERED)
    }

    // OFF→ON: 멤버를 이름 오름차순(동명이인은 id로 안정 정렬)으로 1..N 재채번. 빈 좌석 자동 삽입 없음
    // (관리자가 §12-5 [+ 빈 좌석]으로 추가).
    private fun reorderOffToOn(seatGroupId: String) {
        val members = assignmentPort.findMembersOrderedByGuestName(seatGroupId)
        members.forEachIndexed { index, member -> assignmentPort.updateOrd(member.id, index + 1) }
    }

    private fun SeatGroup.toView(counts: SeatGroupCounts?): SeatGroupView = SeatGroupView(
        id = id,
        groupNo = groupNo,
        label = label,
        numbering = numbering,
        sortOrder = sortOrder,
        assignedCount = counts?.assignedCount ?: 0,
        slotCount = if (numbering) (counts?.slotCount ?: 0) else null,
    )
}
