package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.co.morymaker.api.application.port.`in`.SeatGroupCreateCommand
import kr.co.morymaker.api.application.port.`in`.SeatGroupUpdateCommand
import kr.co.morymaker.api.application.port.out.SeatAssignmentPort
import kr.co.morymaker.api.application.port.out.SeatGroupCounts
import kr.co.morymaker.api.application.port.out.SeatGroupPort
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.seat.SeatAssignment
import kr.co.morymaker.api.domain.seat.SeatGroup
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [SeatGroupService] 단위 테스트 — [SeatGroupPort]/[SeatAssignmentPort]/[EventScopeGuard]를 mock으로
 * 대체해 그룹 CRUD 오케스트레이션·집계 결합·numbering 토글 재해석(M4)만 검증한다.
 *
 * 실 DB cross-tenant 격리·§12-5와 결합된 종합 TC는 Tester(T-G05) 담당.
 */
class SeatGroupServiceTest {

    private val groupPort = mockk<SeatGroupPort>()
    private val assignmentPort = mockk<SeatAssignmentPort>()
    private val eventScopeGuard = mockk<EventScopeGuard>()
    private val service = SeatGroupService(groupPort, assignmentPort, eventScopeGuard)

    private fun sampleGroup(
        id: String = "g1",
        groupNo: Int = 1,
        label: String = "A열",
        numbering: Boolean = true,
        sortOrder: Int = 1,
    ) = SeatGroup(id = id, eventId = "ev1", groupNo = groupNo, label = label, numbering = numbering, sortOrder = sortOrder)

    private fun sampleAssignment(id: String, guestId: String?) =
        SeatAssignment(id = id, eventId = "ev1", seatGroupId = "g1", ord = SeatAssignment.ORD_UNNUMBERED, guestId = guestId)

    // ── listGroups ─────────────────────────────────────────────────

    @Test
    fun `listGroups는 numbering ON 그룹만 slotCount를 채우고 OFF 그룹은 null로 생략한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.findByEvent("ev1") } returns listOf(
            sampleGroup(id = "g1", numbering = true),
            sampleGroup(id = "g2", numbering = false),
        )
        every { assignmentPort.countsByGroup("ev1") } returns listOf(
            SeatGroupCounts(seatGroupId = "g1", slotCount = 8, assignedCount = 3),
            SeatGroupCounts(seatGroupId = "g2", slotCount = 5, assignedCount = 5),
        )

        val result = service.listGroups("ev1")

        val g1 = result.first { it.id == "g1" }
        assertEquals(8, g1.slotCount)
        assertEquals(3, g1.assignedCount)

        val g2 = result.first { it.id == "g2" }
        assertNull(g2.slotCount)
        assertEquals(5, g2.assignedCount)
    }

    @Test
    fun `listGroups는 집계 행이 없는 그룹은 0으로 채운다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.findByEvent("ev1") } returns listOf(sampleGroup(id = "g1", numbering = true))
        every { assignmentPort.countsByGroup("ev1") } returns emptyList()

        val result = service.listGroups("ev1")

        assertEquals(0, result.first().assignedCount)
        assertEquals(0, result.first().slotCount)
    }

    // ── createGroup ────────────────────────────────────────────────

    @Test
    fun `createGroup은 groupNo·sortOrder를 서버가 채번하고 배정 0건으로 응답한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.nextGroupNo("ev1") } returns 3
        every { groupPort.nextSortOrder("ev1") } returns 3
        val inserted = slot<SeatGroup>()
        every { groupPort.insert(capture(inserted)) } returns Unit

        val result = service.createGroup("ev1", SeatGroupCreateCommand(label = "B열", numbering = true))

        assertEquals(3, result.groupNo)
        assertEquals(3, result.sortOrder)
        assertEquals(0, result.assignedCount)
        assertEquals(0, result.slotCount)
        assertEquals("B열", inserted.captured.label)
        verify(exactly = 1) { groupPort.insert(any()) }
    }

    // ── updateGroup ────────────────────────────────────────────────

    @Test
    fun `updateGroup은 그룹이 없으면 NoSuchElementException을 던진다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchById("ev1", "ghost") } returns null

        assertFailsWith<NoSuchElementException> {
            service.updateGroup("ev1", "ghost", SeatGroupUpdateCommand(label = "X", numbering = true))
        }
    }

    @Test
    fun `updateGroup은 numbering 값이 그대로면 M4 재해석을 호출하지 않는다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchById("ev1", "g1") } returns sampleGroup(numbering = true)
        every { groupPort.update(any()) } returns Unit
        every { assignmentPort.countsByGroup("ev1") } returns emptyList()

        service.updateGroup("ev1", "g1", SeatGroupUpdateCommand(label = "A열 변경", numbering = true))

        verify(exactly = 0) { assignmentPort.deleteEmptySeats(any()) }
        verify(exactly = 0) { assignmentPort.findMembersOrderedByGuestName(any()) }
    }

    @Test
    fun `updateGroup은 ON에서 OFF로 토글하면 빈좌석을 삭제하고 남은 멤버 ord를 ORD_UNNUMBERED로 일괄 갱신한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchById("ev1", "g1") } returns sampleGroup(numbering = true)
        every { groupPort.update(any()) } returns Unit
        every { assignmentPort.deleteEmptySeats("g1") } returns Unit
        every { assignmentPort.updateOrdForGroup("g1", SeatAssignment.ORD_UNNUMBERED) } returns Unit
        every { assignmentPort.countsByGroup("ev1") } returns emptyList()

        service.updateGroup("ev1", "g1", SeatGroupUpdateCommand(label = "A열", numbering = false))

        verify(exactly = 1) { assignmentPort.deleteEmptySeats("g1") }
        verify(exactly = 1) { assignmentPort.updateOrdForGroup("g1", SeatAssignment.ORD_UNNUMBERED) }
        verify(exactly = 0) { assignmentPort.findMembersOrderedByGuestName(any()) }
    }

    @Test
    fun `updateGroup은 OFF에서 ON으로 토글하면 멤버를 이름순으로 1부터 재채번한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchById("ev1", "g1") } returns sampleGroup(numbering = false)
        every { groupPort.update(any()) } returns Unit
        every { assignmentPort.findMembersOrderedByGuestName("g1") } returns listOf(
            sampleAssignment("a1", "guestA"),
            sampleAssignment("a2", "guestB"),
            sampleAssignment("a3", "guestC"),
        )
        every { assignmentPort.updateOrd(any(), any()) } returns Unit
        every { assignmentPort.countsByGroup("ev1") } returns emptyList()

        service.updateGroup("ev1", "g1", SeatGroupUpdateCommand(label = "A열", numbering = true))

        verify(exactly = 1) { assignmentPort.updateOrd("a1", 1) }
        verify(exactly = 1) { assignmentPort.updateOrd("a2", 2) }
        verify(exactly = 1) { assignmentPort.updateOrd("a3", 3) }
        verify(exactly = 0) { assignmentPort.deleteEmptySeats(any()) }
    }

    // ── deleteGroup ────────────────────────────────────────────────

    @Test
    fun `deleteGroup은 그룹이 없으면 NoSuchElementException을 던진다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchById("ev1", "ghost") } returns null

        assertFailsWith<NoSuchElementException> { service.deleteGroup("ev1", "ghost") }
    }

    @Test
    fun `deleteGroup은 FK CASCADE·SET NULL에 위임하고 별도 동기화 호출을 하지 않는다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { groupPort.fetchById("ev1", "g1") } returns sampleGroup()
        every { groupPort.delete("ev1", "g1") } returns Unit

        service.deleteGroup("ev1", "g1")

        verify(exactly = 1) { groupPort.delete("ev1", "g1") }
        verify(exactly = 0) { assignmentPort.deleteByGroup(any(), any()) }
    }
}
