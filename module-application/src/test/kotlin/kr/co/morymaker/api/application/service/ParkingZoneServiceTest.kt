package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.co.morymaker.api.application.port.`in`.ZoneCreateCommand
import kr.co.morymaker.api.application.port.`in`.ZoneUpdateCommand
import kr.co.morymaker.api.application.port.out.ParkingSlotTitlePort
import kr.co.morymaker.api.application.port.out.ParkingZonePort
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.parking.ParkingSlotTitle
import kr.co.morymaker.api.domain.parking.ParkingZone
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [ParkingZoneService] 단위 테스트 — [ParkingZonePort]/[ParkingSlotTitlePort]/[EventScopeGuard]를
 * mock으로 대체해 구획 CRUD 오케스트레이션·titleOverrides delete-insert·QR 자리 파생만 검증한다.
 *
 * 종합 격리 TC(cross-tenant)는 Tester(T-Z07) 담당.
 */
class ParkingZoneServiceTest {

    private val zonePort = mockk<ParkingZonePort>()
    private val slotTitlePort = mockk<ParkingSlotTitlePort>()
    private val eventScopeGuard = mockk<EventScopeGuard>()
    private val service = ParkingZoneService(zonePort, slotTitlePort, eventScopeGuard)

    private fun sampleZone(
        id: String = "z1",
        part1: String? = "지하 2층",
        part2: String? = "A구역",
        startNo: Int = 1,
        slotCount: Int = 12,
    ) = ParkingZone(
        id = id, eventId = "ev1", part1 = part1, part2 = part2, part3 = null, part4 = null,
        startNo = startNo, slotCount = slotCount, createdAt = Instant.now(),
    )

    // ── listZones ──────────────────────────────────────────────────

    @Test
    fun `listZones는 zoneName·outdoor를 파생하고 titleOverrides를 zone별로 그룹핑한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { zonePort.findByEvent("ev1") } returns listOf(sampleZone(id = "z1"), sampleZone(id = "z2", part1 = "야외"))
        every { slotTitlePort.findByEventId("ev1") } returns listOf(
            ParkingSlotTitle("z1", 3, "귀빈석"),
            ParkingSlotTitle("z1", 5, "VIP"),
        )

        val result = service.listZones("ev1")

        val z1 = result.first { it.id == "z1" }
        assertEquals("지하 2층 A구역", z1.zoneName)
        assertEquals(false, z1.outdoor)
        assertEquals(mapOf("3" to "귀빈석", "5" to "VIP"), z1.titleOverrides)

        val z2 = result.first { it.id == "z2" }
        assertEquals(true, z2.outdoor)
        assertTrue(z2.titleOverrides.isEmpty())
    }

    // ── createZone ─────────────────────────────────────────────────

    @Test
    fun `createZone은 신규 구획을 삽입하고 override 없이 빈 맵으로 응답한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val inserted = slot<ParkingZone>()
        every { zonePort.insert(capture(inserted)) } returns Unit

        val command = ZoneCreateCommand(part1 = "야외", part2 = "C구역", part3 = null, part4 = null, startNo = 1, slotCount = 8)
        val result = service.createZone("ev1", command)

        assertEquals("야외 C구역", result.zoneName)
        assertEquals(true, result.outdoor)
        assertEquals(8, result.slotCount)
        assertTrue(result.titleOverrides.isEmpty())
        assertEquals("야외", inserted.captured.part1)
        verify(exactly = 1) { zonePort.insert(any()) }
    }

    // ── updateZone ─────────────────────────────────────────────────

    @Test
    fun `updateZone은 titleOverrides가 null이면 타이틀 테이블을 건드리지 않는다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { zonePort.fetchById("ev1", "z1") } returns sampleZone(id = "z1")
        every { zonePort.update(any()) } returns Unit
        every { slotTitlePort.findByZoneId("z1") } returns listOf(ParkingSlotTitle("z1", 3, "귀빈석"))

        val command = ZoneUpdateCommand(
            part1 = "지하 2층", part2 = "A구역", part3 = null, part4 = null,
            startNo = 1, slotCount = 12, titleOverrides = null,
        )
        val result = service.updateZone("ev1", "z1", command)

        assertEquals(mapOf("3" to "귀빈석"), result.titleOverrides)
        verify(exactly = 0) { slotTitlePort.deleteByZoneId(any()) }
        verify(exactly = 0) { slotTitlePort.insertBatch(any()) }
    }

    @Test
    fun `updateZone은 titleOverrides가 non-null이면 전삭제 후 재삽입한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { zonePort.fetchById("ev1", "z1") } returns sampleZone(id = "z1")
        every { zonePort.update(any()) } returns Unit
        every { slotTitlePort.deleteByZoneId("z1") } returns Unit
        val insertedRows = slot<List<ParkingSlotTitle>>()
        every { slotTitlePort.insertBatch(capture(insertedRows)) } returns Unit

        val command = ZoneUpdateCommand(
            part1 = "지하 2층", part2 = "A구역", part3 = null, part4 = null,
            startNo = 1, slotCount = 12,
            titleOverrides = mapOf("3" to "귀빈석", "5" to "VIP", "abc" to "무시대상", "7" to ""),
        )
        val result = service.updateZone("ev1", "z1", command)

        verify(exactly = 1) { slotTitlePort.deleteByZoneId("z1") }
        // "abc"는 정수 파싱 실패로, "7"은 빈 문자열이라 각각 걸러진다 — 2건만 삽입.
        assertEquals(2, insertedRows.captured.size)
        assertEquals(mapOf("3" to "귀빈석", "5" to "VIP"), result.titleOverrides)
    }

    @Test
    fun `updateZone은 titleOverrides가 빈 맵이면 전삭제만 하고 재삽입은 생략한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { zonePort.fetchById("ev1", "z1") } returns sampleZone(id = "z1")
        every { zonePort.update(any()) } returns Unit
        every { slotTitlePort.deleteByZoneId("z1") } returns Unit

        val command = ZoneUpdateCommand(
            part1 = "지하 2층", part2 = "A구역", part3 = null, part4 = null,
            startNo = 1, slotCount = 12, titleOverrides = emptyMap(),
        )
        val result = service.updateZone("ev1", "z1", command)

        verify(exactly = 1) { slotTitlePort.deleteByZoneId("z1") }
        verify(exactly = 0) { slotTitlePort.insertBatch(any()) }
        assertTrue(result.titleOverrides.isEmpty())
    }

    @Test
    fun `updateZone은 구획이 없으면 NoSuchElementException을 던진다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { zonePort.fetchById("ev1", "ghost") } returns null

        val command = ZoneUpdateCommand(
            part1 = "지하 2층", part2 = null, part3 = null, part4 = null,
            startNo = 1, slotCount = 12, titleOverrides = null,
        )
        assertFailsWith<NoSuchElementException> { service.updateZone("ev1", "ghost", command) }
    }

    // ── getSlotsForQr ──────────────────────────────────────────────

    @Test
    fun `getSlotsForQr는 slotCount만큼 자리를 파생하고 override 타이틀을 반영한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { zonePort.fetchById("ev1", "z1") } returns sampleZone(id = "z1", startNo = 1, slotCount = 3)
        every { slotTitlePort.findByZoneId("z1") } returns listOf(ParkingSlotTitle("z1", 2, "귀빈석"))

        val bundle = service.getSlotsForQr("ev1", "z1")

        assertEquals("지하 2층 A구역", bundle.zoneName)
        assertEquals(3, bundle.slots.size)
        assertEquals("z1-01", bundle.slots[0].slotCode)
        assertEquals("지하 2층 A구역 1", bundle.slots[0].slotFullName)
        assertEquals("지하 2층 A구역 귀빈석", bundle.slots[1].slotFullName)
    }

    @Test
    fun `getSlotsForQr는 구획이 없으면 NoSuchElementException을 던진다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { zonePort.fetchById("ev1", "ghost") } returns null

        assertFailsWith<NoSuchElementException> { service.getSlotsForQr("ev1", "ghost") }
    }
}
