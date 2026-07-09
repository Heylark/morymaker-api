package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.co.morymaker.api.application.port.`in`.CheckinResult
import kr.co.morymaker.api.application.port.`in`.CheckinTarget
import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.ParkingLinkPort
import kr.co.morymaker.api.application.port.out.ParkingSlotRef
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.guest.Guest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [CheckinService] 단위 테스트 — SCN 체크인 확정(§5-1)·취소(§5-3)의 상태 전이·멱등성만 검증한다.
 * KIO 경로는 이 REQ 범위 밖(D2 이연)이라 대상 아님.
 */
class CheckinServiceTest {

    private val guestPort = mockk<GuestPort>()
    private val parkingLinkPort = mockk<ParkingLinkPort>()
    private val eventScopeGuard = mockk<EventScopeGuard>()
    private val service = CheckinService(guestPort, parkingLinkPort, eventScopeGuard)

    private fun sampleGuestListItem(
        id: String = "g1",
        status: String = Guest.STATUS_WAITING,
        visitAt: Instant? = null,
    ) = GuestListItem(
        id = id,
        eventId = "ev1",
        name = "김진우",
        org = null,
        title = null,
        phone = null,
        plate = null,
        seatGroupId = null,
        status = status,
        src = Guest.SRC_PRE,
        visitAt = visitAt,
        token = "sample-token",
        createdAt = Instant.now(),
        seatLabel = "A-12",
    )

    @Test
    fun `checkin은 대기중인 참석자를 참석으로 확정하고 CHECKED_IN을 반환한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val guest = sampleGuestListItem(status = Guest.STATUS_WAITING)
        every { guestPort.fetchDetailByToken("ev1", "tok1") } returns guest
        val updated = slot<Guest>()
        every { guestPort.update(capture(updated)) } returns Unit
        every { guestPort.fetchDetailById("ev1", "g1") } returns sampleGuestListItem(status = Guest.STATUS_ATTENDED)
        every { parkingLinkPort.findActiveSlotByGuestId("ev1", "g1") } returns null

        val result = service.checkin("ev1", CheckinTarget.ByToken("tok1"))

        assertEquals(CheckinResult.CHECKED_IN, result.resultCode)
        assertEquals(Guest.STATUS_ATTENDED, updated.captured.status)
        verify(exactly = 1) { guestPort.update(any()) }
    }

    @Test
    fun `checkin은 이미 참석 상태면 재변경 없이 ALREADY_CHECKED_IN을 반환한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val guest = sampleGuestListItem(status = Guest.STATUS_ATTENDED, visitAt = Instant.now())
        every { guestPort.fetchDetailById("ev1", "g1") } returns guest
        every { parkingLinkPort.findActiveSlotByGuestId("ev1", "g1") } returns null

        val result = service.checkin("ev1", CheckinTarget.ByGuestId("g1"))

        assertEquals(CheckinResult.ALREADY_CHECKED_IN, result.resultCode)
        verify(exactly = 0) { guestPort.update(any()) }
    }

    @Test
    fun `checkin은 대상을 찾지 못하면 NoSuchElementException을 던진다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { guestPort.fetchDetailByToken("ev1", "ghost") } returns null

        assertFailsWith<NoSuchElementException> { service.checkin("ev1", CheckinTarget.ByToken("ghost")) }
    }

    @Test
    fun `checkin은 연결된 활성 주차 슬롯이 있으면 구분자를 정규화해 병기한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val guest = sampleGuestListItem(status = Guest.STATUS_ATTENDED, visitAt = Instant.now())
        every { guestPort.fetchDetailById("ev1", "g1") } returns guest
        every { parkingLinkPort.findActiveSlotByGuestId("ev1", "g1") } returns ParkingSlotRef("지하 2층·A구역·3")

        val result = service.checkin("ev1", CheckinTarget.ByGuestId("g1"))

        assertEquals("지하 2층·A구역·3", result.parking?.slotSig)
        assertEquals("지하 2층 A구역 3", result.parking?.display)
    }

    @Test
    fun `cancelCheckin은 참석을 대기로 되돌리고 visit_at을 초기화한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val existing = Guest(
            id = "g1", eventId = "ev1", name = "김진우", org = null, title = null, phone = null,
            plate = null, seatGroupId = null, status = Guest.STATUS_ATTENDED, src = Guest.SRC_PRE,
            visitAt = Instant.now(), token = "sample-token", createdAt = Instant.now(),
        )
        every { guestPort.fetchById("ev1", "g1") } returns existing
        val updated = slot<Guest>()
        every { guestPort.update(capture(updated)) } returns Unit

        val result = service.cancelCheckin("ev1", "g1")

        assertEquals(Guest.STATUS_WAITING, result.status)
        assertNull(result.visitAt)
        assertEquals(Guest.STATUS_WAITING, updated.captured.status)
        assertNull(updated.captured.visitAt)
    }

    @Test
    fun `scanPreview는 조회만 하고 상태를 변경하지 않는다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val guest = sampleGuestListItem(status = Guest.STATUS_WAITING)
        every { guestPort.fetchDetailByToken("ev1", "tok1") } returns guest

        val result = service.scanPreview("ev1", "tok1")

        assertEquals(Guest.STATUS_WAITING, result.status)
        verify(exactly = 0) { guestPort.update(any()) }
    }
}
