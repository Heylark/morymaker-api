package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.BulkAssignCommand
import kr.co.morymaker.api.application.port.`in`.SeatAssignmentListResult
import kr.co.morymaker.api.application.port.`in`.SeatAssignmentUseCase
import kr.co.morymaker.api.application.port.`in`.SeatSlotView
import kr.co.morymaker.api.application.port.out.GuestSeatLinkPort
import kr.co.morymaker.api.application.port.out.SeatAssignmentPort
import kr.co.morymaker.api.application.port.out.SeatGroupPort
import kr.co.morymaker.api.application.port.out.SeatSlotRow
import kr.co.morymaker.api.application.seat.SeatConflictException
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.seat.SeatAssignment
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [SeatAssignmentUseCase] 구현체 — 그룹 배정 조회(§12-4)와 일괄 배정 원자 교체(§12-5, M1 동시성
 * 가드 + M3 `guest.seat_group_id` 동기화)를 담당한다(02-architect §4·§5).
 *
 * [replaceAssignments]는 `@Transactional`(REQUIRED) 단일 메서드가 트랜잭션 경계를 이룬다
 * (self-call·REQUIRES_NEW 없음 — kotlin-conventions §1-4).
 *
 * 헥사고날 레이어: application(service). `internal`: api-app은 [SeatAssignmentUseCase] 인터페이스만
 * 의존한다.
 */
@Service
internal class SeatAssignmentService(
    private val groupPort: SeatGroupPort,
    private val assignmentPort: SeatAssignmentPort,
    private val guestSeatLinkPort: GuestSeatLinkPort,
    private val eventScopeGuard: EventScopeGuard,
) : SeatAssignmentUseCase {

    @Transactional(readOnly = true)
    override fun listAssignments(eventId: String, groupNo: Int, page: Int, size: Int): SeatAssignmentListResult {
        eventScopeGuard.assertAccess(eventId)
        val group = groupPort.fetchByGroupNo(eventId, groupNo) ?: throw NoSuchElementException("좌석 그룹을 찾을 수 없습니다")
        val safePage = page.coerceAtLeast(1)
        val offset = (safePage - 1) * size
        val rows = assignmentPort.findByGroup(eventId, group.id, offset, size)
        val total = assignmentPort.countByGroup(eventId, group.id)
        return SeatAssignmentListResult(items = rows.map { it.toView(group.groupNo) }, total = total)
    }

    @Transactional
    override fun replaceAssignments(eventId: String, command: BulkAssignCommand): List<SeatSlotView> {
        eventScopeGuard.assertAccess(eventId)
        val group = groupPort.fetchByGroupNo(eventId, command.groupNo) ?: throw NoSuchElementException("좌석 그룹을 찾을 수 없습니다")

        val nonNullGuestIds = command.assignments.mapNotNull { it.guestId }
        require(nonNullGuestIds.size == nonNullGuestIds.toSet().size) { "같은 참석자를 중복 배정할 수 없습니다" }

        // payload 검증(M1) — numbering ON은 ord가 1..N 연속·유일해야 한다. 문자·객체 ord는 DTO
        // 타입이 Int라 역직렬화에서 거부되지만, 소수(예: 12.7)는 Jackson 기본 설정이 정수로 절삭해
        // 받아들인다(스펙 "소수 거부"와의 갭 — tech-debt 등록). 절삭값이 1..N을 벗어나면 아래 검증이
        // 거부하고, 벗어나지 않으면 유효 정수로 저장돼 데이터 무결성은 유지된다. OFF는 서버가 무시하고
        // ORD_UNNUMBERED로 강제하므로 payload ord 검증이 불필요하다.
        if (group.numbering) {
            val ords = command.assignments.map { it.ord }
            require(ords.toSet() == (1..ords.size).toSet()) { "좌석 번호는 1부터 연속된 정수여야 합니다" }
        }

        if (nonNullGuestIds.isNotEmpty()) {
            val validIds = guestSeatLinkPort.filterExistingIds(eventId, nonNullGuestIds)
            require(validIds.size == nonNullGuestIds.size) { "존재하지 않는 참석자가 포함되어 있습니다" }

            // app-level assignedElsewhere 사전검사(친절 에러) — TOCTOU라 최종 방어는 아니다.
            // 최종 방어는 guardingSeatUniqueness의 UNIQUE(guest_id) 위반 catch.
            val elsewhere = assignmentPort.findGroupIdsByGuestIds(eventId, nonNullGuestIds).filterValues { it != group.id }
            if (elsewhere.isNotEmpty()) throw SeatConflictException("다른 그룹에 이미 배정된 참석자가 있습니다")
        }

        // M3 동기화 대상 계산 — 원자 교체(DELETE) 전에 현재 멤버를 읽어둔다.
        val previousGuestIds = assignmentPort.findGuestIdsByGroup(group.id)

        val newRows = command.assignments.map { entry ->
            SeatAssignment(
                id = UUID.randomUUID().toString(),
                eventId = eventId,
                seatGroupId = group.id,
                ord = if (group.numbering) entry.ord else SeatAssignment.ORD_UNNUMBERED,
                guestId = entry.guestId,
            )
        }

        guardingSeatUniqueness {
            assignmentPort.deleteByGroup(eventId, group.id)
            assignmentPort.insertBatch(newRows)
        }

        val newGuestIds = nonNullGuestIds.toSet()
        val removedGuestIds = previousGuestIds.filterNot { it in newGuestIds }
        if (removedGuestIds.isNotEmpty()) guestSeatLinkPort.updateSeatGroupId(removedGuestIds, null)
        if (nonNullGuestIds.isNotEmpty()) guestSeatLinkPort.updateSeatGroupId(nonNullGuestIds, group.id)

        val refreshed = assignmentPort.findByGroup(eventId, group.id, offset = null, limit = null)
        return refreshed.map { it.toView(group.groupNo) }
    }

    // guest_id UNIQUE(1인 다좌석 경쟁 후착)·DELETE 갭 경합을 좌석 충돌(409)로 번역한다.
    // ParkingRecordService.guardingSlotUniqueness 그대로 미러.
    private inline fun <T> guardingSeatUniqueness(block: () -> T): T =
        try {
            block()
        } catch (e: DuplicateKeyException) { // 선착이 이미 좌석 점유(guest_id UNIQUE 위반)
            throw SeatConflictException("이미 다른 그룹에 배정된 참석자가 있습니다")
        } catch (e: PessimisticLockingFailureException) { // 동일 그룹 동시 원자교체 락 경합
            throw SeatConflictException("다른 요청과 충돌했습니다. 다시 시도해 주세요")
        }

    private fun SeatSlotRow.toView(groupNo: Int): SeatSlotView = SeatSlotView(
        id = id,
        groupNo = groupNo,
        ord = ord,
        guestId = guestId,
        guestName = guestName,
        empty = if (guestId == null) true else null,
    )
}
