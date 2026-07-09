package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.co.morymaker.api.application.port.`in`.GuestImportRow
import kr.co.morymaker.api.application.port.`in`.GuestListResult
import kr.co.morymaker.api.application.port.`in`.GuestSearchCommand
import kr.co.morymaker.api.application.port.`in`.RegisterGuestCommand
import kr.co.morymaker.api.application.port.`in`.UpdateGuestCommand
import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.ParkingLinkPort
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.guest.Guest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GuestService] 단위 테스트 — [GuestPort]/[ParkingLinkPort]/[EventScopeGuard]를 mock으로
 * 대체해 CRUD 오케스트레이션·주차 지연매칭(mapGuestParking)·엑셀 병합 분류 로직만 검증한다.
 *
 * 종합 격리 TC(cross-tenant)·byte-identical 회귀 TC 등은 Tester(T-009) 담당.
 */
class GuestServiceTest {

    private val guestPort = mockk<GuestPort>()
    private val parkingLinkPort = mockk<ParkingLinkPort>()
    private val eventScopeGuard = mockk<EventScopeGuard>()
    private val service = GuestService(guestPort, parkingLinkPort, eventScopeGuard, GuestTokenGenerator())

    private fun sampleGuest(
        id: String = "g1",
        name: String = "김진우",
        phone: String? = "010-1234-5678",
        plate: String? = null,
        status: String = Guest.STATUS_WAITING,
        token: String = "sample-token",
    ) = Guest(
        id = id,
        eventId = "ev1",
        name = name,
        org = null,
        title = null,
        phone = phone,
        plate = plate,
        seatGroupId = null,
        status = status,
        src = Guest.SRC_PRE,
        visitAt = null,
        token = token,
        createdAt = Instant.now(),
    )

    private fun sampleGuestListItem(
        id: String = "g1",
        name: String = "김진우",
        org: String? = null,
        phone: String? = "010-1234-5678",
        status: String = Guest.STATUS_WAITING,
        token: String = "sample-token",
    ) = GuestListItem(
        id = id,
        eventId = "ev1",
        name = name,
        org = org,
        title = null,
        phone = phone,
        plate = null,
        seatGroupId = null,
        status = status,
        src = Guest.SRC_PRE,
        visitAt = null,
        token = token,
        createdAt = Instant.now(),
        seatLabel = null,
    )

    // ── registerGuest ──────────────────────────────────────────────

    @Test
    fun `registerGuest는 대기 상태·현장 등록 기본값으로 저장하고 plate 없으면 주차매핑을 시도하지 않는다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { guestPort.existsByToken(any()) } returns false
        every { guestPort.insert(any()) } returns Unit

        val command = RegisterGuestCommand(
            name = "김진우", org = "모리메이커", title = "대표", phone = "010-1111-2222",
            plate = null, seatGroupId = null, src = null,
        )
        val result = service.registerGuest("ev1", command)

        assertEquals(Guest.STATUS_WAITING, result.status)
        assertEquals(Guest.SRC_ONSITE, result.src)
        assertEquals("김진우", result.name)
        verify(exactly = 1) { guestPort.insert(any()) }
        verify(exactly = 0) { parkingLinkPort.findActiveRecordIdByPlate(any(), any()) }
    }

    @Test
    fun `registerGuest는 plate가 매칭되면 방문 상태로 전이하고 parking_record를 백필한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { guestPort.existsByToken(any()) } returns false
        every { guestPort.insert(any()) } returns Unit
        every { parkingLinkPort.findActiveRecordIdByPlate("ev1", "123가4568") } returns "record-1"
        every { parkingLinkPort.linkGuest("record-1", any()) } returns Unit
        every { guestPort.update(any()) } returns Unit

        val command = RegisterGuestCommand(
            name = "박서연", org = null, title = null, phone = null,
            plate = "123가 4568", seatGroupId = null, src = "사전",
        )
        val result = service.registerGuest("ev1", command)

        assertEquals(Guest.STATUS_VISITED, result.status)
        assertNotNull(result.visitAt)
        verify(exactly = 1) { parkingLinkPort.linkGuest("record-1", result.id) }
        verify(exactly = 1) { guestPort.update(any()) }
    }

    @Test
    fun `registerGuest는 plate 매칭 실패 시 대기 상태를 유지하고 update를 호출하지 않는다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { guestPort.existsByToken(any()) } returns false
        every { guestPort.insert(any()) } returns Unit
        every { parkingLinkPort.findActiveRecordIdByPlate("ev1", any()) } returns null

        val command = RegisterGuestCommand(
            name = "이도현", org = null, title = null, phone = null,
            plate = "999나9999", seatGroupId = null, src = null,
        )
        val result = service.registerGuest("ev1", command)

        assertEquals(Guest.STATUS_WAITING, result.status)
        verify(exactly = 0) { guestPort.update(any()) }
    }

    @Test
    fun `registerGuest는 토큰 충돌 시 재생성해 유일한 토큰을 발급한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { guestPort.existsByToken(any()) } returnsMany listOf(true, false)
        every { guestPort.insert(any()) } returns Unit

        val command = RegisterGuestCommand(
            name = "정하은", org = null, title = null, phone = null,
            plate = null, seatGroupId = null, src = null,
        )
        val result = service.registerGuest("ev1", command)

        assertTrue(result.token.isNotBlank())
        verify(exactly = 2) { guestPort.existsByToken(any()) }
    }

    // ── updateGuest ────────────────────────────────────────────────

    @Test
    fun `updateGuest는 미지정 필드를 기존 값으로 보존한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val existing = sampleGuest(id = "g1", name = "기존이름", phone = "010-0000-0000")
        every { guestPort.fetchById("ev1", "g1") } returns existing
        every { guestPort.update(any()) } returns Unit

        val command = UpdateGuestCommand(
            name = null, org = "새소속", title = null, phone = null, plate = null, seatGroupId = null,
        )
        val result = service.updateGuest("ev1", "g1", command)

        assertEquals("기존이름", result.name)
        assertEquals("새소속", result.org)
        assertEquals("010-0000-0000", result.phone)
        verify(exactly = 1) { guestPort.update(any()) }
    }

    @Test
    fun `updateGuest는 plate 변경 시 주차 지연매칭을 재시도한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val existing = sampleGuest(id = "g1", plate = null, status = Guest.STATUS_WAITING)
        every { guestPort.fetchById("ev1", "g1") } returns existing
        every { guestPort.update(any()) } returns Unit
        every { parkingLinkPort.findActiveRecordIdByPlate("ev1", "12가3456") } returns "record-2"
        every { parkingLinkPort.linkGuest("record-2", "g1") } returns Unit

        val command = UpdateGuestCommand(
            name = null, org = null, title = null, phone = null, plate = "12가3456", seatGroupId = null,
        )
        val result = service.updateGuest("ev1", "g1", command)

        assertEquals(Guest.STATUS_VISITED, result.status)
        // 1) 필드 변경분 선저장 2) 매칭 성공 후 상태 전이 저장 — 총 2회.
        verify(exactly = 2) { guestPort.update(any()) }
    }

    // ── cancelGuest ────────────────────────────────────────────────

    @Test
    fun `cancelGuest는 상태를 취소로 전환한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val existing = sampleGuest(id = "g1", status = Guest.STATUS_WAITING)
        every { guestPort.fetchById("ev1", "g1") } returns existing
        val updated = slot<Guest>()
        every { guestPort.update(capture(updated)) } returns Unit

        val result = service.cancelGuest("ev1", "g1", deleteSmsLog = true)

        assertEquals(Guest.STATUS_CANCELLED, result.status)
        assertEquals(Guest.STATUS_CANCELLED, updated.captured.status)
    }

    // ── listGuests / searchState ──────────────────────────────────

    @Test
    fun `listGuests는 q가 있고 매칭 0건이면 searchState NONE을 반환한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { guestPort.search("ev1", any()) } returns emptyList()
        every { guestPort.countSearch("ev1", any()) } returns 0

        val result = service.listGuests("ev1", GuestSearchCommand(q = "없는이름"))

        assertEquals(GuestListResult.SEARCH_STATE_NONE, result.searchState)
    }

    @Test
    fun `listGuests는 q 없이 조회하면 searchState를 계산하지 않는다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { guestPort.search("ev1", any()) } returns emptyList()
        every { guestPort.countSearch("ev1", any()) } returns 0

        val result = service.listGuests("ev1", GuestSearchCommand())

        assertNull(result.searchState)
    }

    // ── import 분류(§4-5·4-6 공유 로직) ────────────────────────────

    @Test
    fun `previewImport는 phone 매칭 기존 참석자를 updated로, 미매칭을 new로, 이름 누락을 invalid로 분류한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val existing = sampleGuestListItem(id = "g1", name = "김진우", phone = "010-1234-5678")
        every { guestPort.search("ev1", any()) } returns listOf(existing)

        val rows = listOf(
            GuestImportRow(2, "김진우", null, null, "010-1234-5678", null, null),
            GuestImportRow(3, "박서연", null, null, "010-9999-0000", null, null),
            GuestImportRow(4, null, null, null, null, null, null),
        )

        val result = service.previewImport("ev1", rows)

        assertEquals(1, result.newCount)
        assertEquals(1, result.updatedCount)
        assertEquals(0, result.excludedCount)
        assertEquals(1, result.invalidRows.size)
        assertEquals(4, result.invalidRows.first().rowNumber)
    }

    @Test
    fun `previewImport는 업로드 명단에 없는 기존 참석자를 제외 대상으로 집계한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val existing = sampleGuestListItem(id = "g1", name = "김진우", phone = "010-1234-5678")
        every { guestPort.search("ev1", any()) } returns listOf(existing)

        val rows = listOf(GuestImportRow(2, "박서연", null, null, "010-9999-0000", null, null))

        val result = service.previewImport("ev1", rows)

        assertEquals(1, result.newCount)
        assertEquals(1, result.excludedCount)
    }

    @Test
    fun `previewImport는 phone 없는 업로드 행을 이름이 같아도 항상 신규로 분류한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val existing = sampleGuestListItem(id = "g1", name = "김진우", phone = "010-1234-5678")
        every { guestPort.search("ev1", any()) } returns listOf(existing)

        val rows = listOf(GuestImportRow(2, "김진우", null, null, null, null, null))

        val result = service.previewImport("ev1", rows)

        assertEquals(1, result.newCount)
        assertEquals(0, result.updatedCount)
        assertEquals(1, result.excludedCount)
    }

    @Test
    fun `confirmImport는 매칭된 기존 참석자의 token과 상태를 보존한 채 필드만 갱신한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val existing = sampleGuestListItem(
            id = "g1", name = "김진우", org = "구소속", phone = "010-1234-5678",
            status = Guest.STATUS_VISITED, token = "existing-token",
        )
        every { guestPort.search("ev1", any()) } returns listOf(existing)
        val updated = slot<Guest>()
        every { guestPort.update(capture(updated)) } returns Unit

        val rows = listOf(GuestImportRow(2, "김진우", "새소속", null, "010-1234-5678", null, null))

        val result = service.confirmImport("ev1", rows)

        assertEquals(1, result.updatedCount)
        assertEquals("existing-token", updated.captured.token)
        assertEquals(Guest.STATUS_VISITED, updated.captured.status)
        assertEquals("새소속", updated.captured.org)
    }

    @Test
    fun `confirmImport는 업로드 명단에 없는 기존 참석자를 취소 상태로 전환하고 신규 행은 삽입한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val existing = sampleGuestListItem(id = "g1", name = "김진우", phone = "010-1234-5678", status = Guest.STATUS_WAITING)
        every { guestPort.search("ev1", any()) } returns listOf(existing)
        val updated = slot<Guest>()
        every { guestPort.update(capture(updated)) } returns Unit
        every { guestPort.existsByToken(any()) } returns false
        every { guestPort.insert(any()) } returns Unit

        val rows = listOf(GuestImportRow(2, "박서연", null, null, "010-9999-0000", null, null))

        val result = service.confirmImport("ev1", rows)

        assertEquals(1, result.cancelledCount)
        assertEquals(Guest.STATUS_CANCELLED, updated.captured.status)
        verify(exactly = 1) { guestPort.insert(any()) }
    }
}
