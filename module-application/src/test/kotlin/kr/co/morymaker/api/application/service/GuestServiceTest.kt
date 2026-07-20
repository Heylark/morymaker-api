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
import kr.co.morymaker.api.application.port.out.SmsLogPort
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.guest.Guest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [GuestService] 단위 테스트 — [GuestPort]/[EventScopeGuard]/[GuestWriteSupport]를 mock으로
 * 대체해 행사 스코프 게이트 호출 순서·필드 병합·CRUD 오케스트레이션·엑셀 병합 분류 로직만
 * 검증한다.
 *
 * 신규 생성·주차 지연매칭·토큰 발급의 실제 쓰기 로직은 [GuestWriteSupport]로 이동했으므로
 * 그 세부 동작(plate 매칭 성공/실패, 토큰 충돌 재시도)은 [GuestWriteSupportTest]가 검증한다.
 * 종합 격리 테스트(cross-tenant)·byte-identical 회귀 테스트 등은 Tester 담당.
 */
class GuestServiceTest {

    private val guestPort = mockk<GuestPort>()
    private val eventScopeGuard = mockk<EventScopeGuard>()
    private val guestWriteSupport = mockk<GuestWriteSupport>()
    private val smsLogPort = mockk<SmsLogPort>()
    private val service = GuestService(guestPort, eventScopeGuard, guestWriteSupport, smsLogPort)

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

    // ── registerGuest — assertAccess 선두 유지 + GuestWriteSupport 위임(동작 보존) ──────

    @Test
    fun `registerGuest는 행사 스코프 게이트를 먼저 통과한 뒤 GuestWriteSupport에 위임한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val expected = sampleGuest(status = Guest.STATUS_WAITING, name = "김진우")
        every { guestWriteSupport.createGuest("ev1", any()) } returns expected

        val command = RegisterGuestCommand(
            name = "김진우", org = "모리메이커", title = "대표", phone = "010-1111-2222",
            plate = null, seatGroupId = null, src = null,
        )
        val result = service.registerGuest("ev1", command)

        assertEquals(expected, result)
        verify(exactly = 1) { eventScopeGuard.assertAccess("ev1") }
        verify(exactly = 1) { guestWriteSupport.createGuest("ev1", command) }
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
        verify(exactly = 0) { guestWriteSupport.mapGuestParking(any(), any()) }
    }

    @Test
    fun `updateGuest는 plate 변경 시 필드를 먼저 저장한 뒤 GuestWriteSupport의 지연매칭을 재시도한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val existing = sampleGuest(id = "g1", plate = null, status = Guest.STATUS_WAITING)
        every { guestPort.fetchById("ev1", "g1") } returns existing
        every { guestPort.update(any()) } returns Unit
        val matched = sampleGuest(id = "g1", plate = "12가3456", status = Guest.STATUS_VISITED)
        every { guestWriteSupport.mapGuestParking("ev1", any()) } returns matched

        val command = UpdateGuestCommand(
            name = null, org = null, title = null, phone = null, plate = "12가3456", seatGroupId = null,
        )
        val result = service.updateGuest("ev1", "g1", command)

        assertEquals(Guest.STATUS_VISITED, result.status)
        // 1) 필드 변경분 선저장(guestPort.update) 2) 지연매칭 위임(guestWriteSupport) — 각각 1회.
        verify(exactly = 1) { guestPort.update(any()) }
        verify(exactly = 1) { guestWriteSupport.mapGuestParking("ev1", any()) }
    }

    // ── cancelGuest ────────────────────────────────────────────────

    @Test
    fun `cancelGuest는 상태를 취소로 전환하고 deleteSmsLog=true면 발송 이력도 함께 삭제한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val existing = sampleGuest(id = "g1", status = Guest.STATUS_WAITING)
        every { guestPort.fetchById("ev1", "g1") } returns existing
        val updated = slot<Guest>()
        every { guestPort.update(capture(updated)) } returns Unit
        every { smsLogPort.deleteByGuest("ev1", "g1") } returns Unit

        val result = service.cancelGuest("ev1", "g1", deleteSmsLog = true)

        assertEquals(Guest.STATUS_CANCELLED, result.status)
        assertEquals(Guest.STATUS_CANCELLED, updated.captured.status)
        verify(exactly = 1) { smsLogPort.deleteByGuest("ev1", "g1") }
    }

    @Test
    fun `cancelGuest는 deleteSmsLog=false면 발송 이력을 건드리지 않는다(byte-identical 경로)`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val existing = sampleGuest(id = "g1", status = Guest.STATUS_WAITING)
        every { guestPort.fetchById("ev1", "g1") } returns existing
        val updated = slot<Guest>()
        every { guestPort.update(capture(updated)) } returns Unit

        val result = service.cancelGuest("ev1", "g1", deleteSmsLog = false)

        assertEquals(Guest.STATUS_CANCELLED, result.status)
        assertEquals(Guest.STATUS_CANCELLED, updated.captured.status)
        verify(exactly = 0) { smsLogPort.deleteByGuest(any(), any()) }
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
        verify(exactly = 0) { guestWriteSupport.mapGuestParking(any(), any()) }
    }

    @Test
    fun `confirmImport는 업로드 명단에 없는 기존 참석자를 취소 상태로 전환하고 신규 행은 GuestWriteSupport로 토큰을 발급받아 삽입한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val existing = sampleGuestListItem(id = "g1", name = "김진우", phone = "010-1234-5678", status = Guest.STATUS_WAITING)
        every { guestPort.search("ev1", any()) } returns listOf(existing)
        val updated = slot<Guest>()
        every { guestPort.update(capture(updated)) } returns Unit
        every { guestWriteSupport.generateUniqueToken() } returns "new-token"
        every { guestPort.insert(any()) } returns Unit

        val rows = listOf(GuestImportRow(2, "박서연", null, null, "010-9999-0000", null, null))

        val result = service.confirmImport("ev1", rows)

        assertEquals(1, result.cancelledCount)
        assertEquals(Guest.STATUS_CANCELLED, updated.captured.status)
        verify(exactly = 1) { guestPort.insert(any()) }
        verify(exactly = 1) { guestWriteSupport.generateUniqueToken() }
        // 신규 행의 plate가 null이라 지연매칭 위임은 발생하지 않는다.
        verify(exactly = 0) { guestWriteSupport.mapGuestParking(any(), any()) }
    }
}
