package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.co.morymaker.api.application.port.`in`.AssignmentEntry
import kr.co.morymaker.api.application.port.`in`.BulkAssignCommand
import kr.co.morymaker.api.application.port.out.GuestSeatLinkPort
import kr.co.morymaker.api.application.port.out.SeatAssignmentPort
import kr.co.morymaker.api.application.port.out.SeatGroupPort
import kr.co.morymaker.api.application.port.out.SeatSlotRow
import kr.co.morymaker.api.application.seat.SeatConflictException
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.seat.SeatAssignment
import kr.co.morymaker.api.domain.seat.SeatGroup
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.PessimisticLockingFailureException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SeatAssignmentService] 단위 테스트 — [SeatGroupPort]/[SeatAssignmentPort]/[GuestSeatLinkPort]/
 * [EventScopeGuard]를 mock으로 대체해 payload 검증(M1)·assignedElsewhere 사전검사·
 * `guest.seat_group_id` 동기화(M3)·예외 번역 경로만 검증한다.
 *
 * 실 DB `guest_id` UNIQUE 동시성(cross-group 1인다좌석)·cross-tenant 격리 종합 TC는
 * Tester(T-A08, `SeatAssignmentConcurrencyIntegrationTest`) 담당 — mock은 SQL 무결성을
 * 우회하므로 동시성 최종 방어 자체는 여기서 검증하지 않는다.
 */
class SeatAssignmentServiceTest {

    private val groupPort = mockk<SeatGroupPort>()
    private val assignmentPort = mockk<SeatAssignmentPort>()
    private val guestSeatLinkPort = mockk<GuestSeatLinkPort>()
    private val eventScopeGuard = mockk<EventScopeGuard>()
    private val service = SeatAssignmentService(groupPort, assignmentPort, guestSeatLinkPort, eventScopeGuard)

    private fun numberedGroup(id: String = "g1", groupNo: Int = 9) =
        SeatGroup(id = id, eventId = "ev1", groupNo = groupNo, label = "A열", numbering = true, sortOrder = 1)

    private fun unnumberedGroup(id: String = "g2", groupNo: Int = 1) =
        SeatGroup(id = id, eventId = "ev1", groupNo = groupNo, label = "1번 테이블", numbering = false, sortOrder = 2)

    private fun stubNoElsewhere() {
        every { assignmentPort.findGuestIdsByGroup(any()) } returns emptyList()
        every { assignmentPort.findGroupIdsByGuestIds("ev1", any()) } returns emptyMap()
    }

    // ── listAssignments ────────────────────────────────────────────

    @Test
    fun `listAssignments는 그룹이 없으면 NoSuchElementException을 던진다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchByGroupNo("ev1", 99) } returns null

        assertFailsWith<NoSuchElementException> { service.listAssignments("ev1", 99, 1, 50) }
    }

    @Test
    fun `listAssignments는 빈 좌석은 empty=true로, 배정된 좌석은 guestName을 채워 반환한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchByGroupNo("ev1", 9) } returns numberedGroup()
        every { assignmentPort.findByGroup("ev1", "g1", 0, 50) } returns listOf(
            SeatSlotRow(id = "s1", ord = 1, guestId = "u1", guestName = "이서연"),
            SeatSlotRow(id = "s2", ord = 2, guestId = null, guestName = null),
        )
        every { assignmentPort.countByGroup("ev1", "g1") } returns 2

        val result = service.listAssignments("ev1", 9, 1, 50)

        assertEquals(2, result.total)
        val slot1 = result.items.first { it.id == "s1" }
        assertEquals("이서연", slot1.guestName)
        assertNull(slot1.empty)
        val slot2 = result.items.first { it.id == "s2" }
        assertEquals(true, slot2.empty)
    }

    @Test
    fun `listAssignments는 page 2에서 offset을 size만큼 계산한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchByGroupNo("ev1", 1) } returns unnumberedGroup()
        every { assignmentPort.findByGroup("ev1", "g2", 50, 50) } returns emptyList()
        every { assignmentPort.countByGroup("ev1", "g2") } returns 0

        service.listAssignments("ev1", 1, 2, 50)

        verify(exactly = 1) { assignmentPort.findByGroup("ev1", "g2", 50, 50) }
    }

    // ── replaceAssignments — payload 검증 ──────────────────────────

    @Test
    fun `replaceAssignments는 동일 guestId가 중복 배정되면 IllegalArgumentException을 던진다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchByGroupNo("ev1", 9) } returns numberedGroup()

        val command = BulkAssignCommand(
            groupNo = 9,
            assignments = listOf(AssignmentEntry(1, "u1"), AssignmentEntry(2, "u1")),
        )

        assertFailsWith<IllegalArgumentException> { service.replaceAssignments("ev1", command) }
    }

    @Test
    fun `replaceAssignments는 numbering ON에서 ord가 1부터 연속되지 않으면 IllegalArgumentException을 던진다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchByGroupNo("ev1", 9) } returns numberedGroup()

        val command = BulkAssignCommand(
            groupNo = 9,
            assignments = listOf(AssignmentEntry(1, null), AssignmentEntry(3, null)),
        )

        assertFailsWith<IllegalArgumentException> { service.replaceAssignments("ev1", command) }
    }

    @Test
    fun `replaceAssignments는 event 소속이 아닌 guestId가 포함되면 IllegalArgumentException을 던진다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchByGroupNo("ev1", 9) } returns numberedGroup()
        every { guestSeatLinkPort.filterExistingIds("ev1", listOf("u1")) } returns emptySet()

        val command = BulkAssignCommand(groupNo = 9, assignments = listOf(AssignmentEntry(1, "u1")))

        assertFailsWith<IllegalArgumentException> { service.replaceAssignments("ev1", command) }
    }

    @Test
    fun `replaceAssignments는 다른 그룹에 이미 배정된 참석자가 있으면 SeatConflictException을 던진다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchByGroupNo("ev1", 9) } returns numberedGroup()
        every { guestSeatLinkPort.filterExistingIds("ev1", listOf("u1")) } returns setOf("u1")
        every { assignmentPort.findGroupIdsByGuestIds("ev1", listOf("u1")) } returns mapOf("u1" to "다른그룹")

        val command = BulkAssignCommand(groupNo = 9, assignments = listOf(AssignmentEntry(1, "u1")))

        assertFailsWith<SeatConflictException> { service.replaceAssignments("ev1", command) }
    }

    // ── replaceAssignments — 정상 경로 + M3 동기화 ─────────────────

    @Test
    fun `replaceAssignments는 numbering ON 그룹에서 원자 교체 후 추가·제거 guest를 각각 동기화한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchByGroupNo("ev1", 9) } returns numberedGroup()
        every { guestSeatLinkPort.filterExistingIds("ev1", listOf("u2")) } returns setOf("u2")
        every { assignmentPort.findGroupIdsByGuestIds("ev1", listOf("u2")) } returns emptyMap()
        every { assignmentPort.findGuestIdsByGroup("g1") } returns listOf("u1")
        val deleted = mutableListOf<String>()
        every { assignmentPort.deleteByGroup("ev1", "g1") } answers { deleted.add("g1") }
        val insertedRows = slot<List<SeatAssignment>>()
        every { assignmentPort.insertBatch(capture(insertedRows)) } returns Unit
        every { guestSeatLinkPort.updateSeatGroupId(listOf("u1"), null) } returns Unit
        every { guestSeatLinkPort.updateSeatGroupId(listOf("u2"), "g1") } returns Unit
        every { assignmentPort.findByGroup("ev1", "g1", null, null) } returns listOf(
            SeatSlotRow(id = "new1", ord = 1, guestId = "u2", guestName = "김민준"),
        )

        val command = BulkAssignCommand(groupNo = 9, assignments = listOf(AssignmentEntry(1, "u2")))
        val result = service.replaceAssignments("ev1", command)

        assertEquals(listOf("g1"), deleted)
        assertEquals(1, insertedRows.captured.size)
        assertEquals("u2", insertedRows.captured.first().guestId)
        assertEquals(1, result.size)
        assertEquals("김민준", result.first().guestName)
        verify(exactly = 1) { guestSeatLinkPort.updateSeatGroupId(listOf("u1"), null) }
        verify(exactly = 1) { guestSeatLinkPort.updateSeatGroupId(listOf("u2"), "g1") }
    }

    @Test
    fun `replaceAssignments는 numbering OFF 그룹에서 payload ord를 무시하고 ORD_UNNUMBERED로 강제한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchByGroupNo("ev1", 1) } returns unnumberedGroup()
        every { guestSeatLinkPort.filterExistingIds("ev1", listOf("u1")) } returns setOf("u1")
        stubNoElsewhere()
        every { assignmentPort.deleteByGroup("ev1", "g2") } returns Unit
        val insertedRows = slot<List<SeatAssignment>>()
        every { assignmentPort.insertBatch(capture(insertedRows)) } returns Unit
        every { guestSeatLinkPort.updateSeatGroupId(any(), any()) } returns Unit
        every { assignmentPort.findByGroup("ev1", "g2", null, null) } returns emptyList()

        val command = BulkAssignCommand(groupNo = 1, assignments = listOf(AssignmentEntry(ord = 1, guestId = "u1")))
        service.replaceAssignments("ev1", command)

        assertEquals(SeatAssignment.ORD_UNNUMBERED, insertedRows.captured.first().ord)
    }

    @Test
    fun `replaceAssignments는 assignments가 빈 리스트면 그룹을 전체 비운다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchByGroupNo("ev1", 9) } returns numberedGroup()
        every { assignmentPort.findGuestIdsByGroup("g1") } returns listOf("u1", "u2")
        every { assignmentPort.deleteByGroup("ev1", "g1") } returns Unit
        every { assignmentPort.insertBatch(emptyList()) } returns Unit
        every { guestSeatLinkPort.updateSeatGroupId(listOf("u1", "u2"), null) } returns Unit
        every { assignmentPort.findByGroup("ev1", "g1", null, null) } returns emptyList()

        val result = service.replaceAssignments("ev1", BulkAssignCommand(groupNo = 9, assignments = emptyList()))

        assertTrue(result.isEmpty())
        verify(exactly = 1) { guestSeatLinkPort.updateSeatGroupId(listOf("u1", "u2"), null) }
        verify(exactly = 0) { guestSeatLinkPort.updateSeatGroupId(any(), "g1") }
    }

    // ── replaceAssignments — 동시성 예외 번역 ──────────────────────

    @Test
    fun `replaceAssignments는 insertBatch에서 DuplicateKeyException이 나면 SeatConflictException으로 번역한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchByGroupNo("ev1", 9) } returns numberedGroup()
        every { guestSeatLinkPort.filterExistingIds("ev1", listOf("u1")) } returns setOf("u1")
        stubNoElsewhere()
        every { assignmentPort.deleteByGroup("ev1", "g1") } returns Unit
        every { assignmentPort.insertBatch(any()) } throws DuplicateKeyException("uq_seatassign_guest 위반")

        val command = BulkAssignCommand(groupNo = 9, assignments = listOf(AssignmentEntry(1, "u1")))

        assertFailsWith<SeatConflictException> { service.replaceAssignments("ev1", command) }
    }

    @Test
    fun `replaceAssignments는 deleteByGroup에서 락 경합이 나면 SeatConflictException으로 번역한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchByGroupNo("ev1", 9) } returns numberedGroup()
        stubNoElsewhere()
        every { assignmentPort.deleteByGroup("ev1", "g1") } throws object : PessimisticLockingFailureException("경합") {}

        val command = BulkAssignCommand(groupNo = 9, assignments = emptyList())

        assertFailsWith<SeatConflictException> { service.replaceAssignments("ev1", command) }
    }
}
