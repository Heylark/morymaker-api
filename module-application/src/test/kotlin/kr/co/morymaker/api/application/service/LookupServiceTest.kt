package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.co.morymaker.api.application.port.`in`.GuestListResult
import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.GuestSearchQuery
import kr.co.morymaker.api.application.port.out.ParkingLinkPort
import kr.co.morymaker.api.application.port.out.ParkingSlotRef
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.guest.Guest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LookupService] 단위 테스트 — searchAny(이름 부분일치 ∪ 차량 뒷자리 매칭) 필터링·3상태·좌석/주차
 * 병기(§9-1)만 검증한다. 실 SQL 경로(전체 조회·좌석/주차 실 DB read-back)는 `LookupControllerTest`가
 * 실 MariaDB로 검증한다.
 */
class LookupServiceTest {

    private val guestPort = mockk<GuestPort>()
    private val parkingLinkPort = mockk<ParkingLinkPort>()
    private val eventScopeGuard = mockk<EventScopeGuard>()
    private val service = LookupService(guestPort, parkingLinkPort, eventScopeGuard)

    private fun sampleGuestListItem(
        id: String,
        name: String,
        plate: String? = null,
    ) = GuestListItem(
        id = id,
        eventId = "ev1",
        name = name,
        org = null,
        title = null,
        phone = null,
        plate = plate,
        seatGroupId = null,
        status = Guest.STATUS_WAITING,
        src = Guest.SRC_PRE,
        visitAt = null,
        token = "token-$id",
        createdAt = Instant.now(),
        seatLabel = null,
    )

    private fun stubSearch(guests: List<GuestListItem>) {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { guestPort.search("ev1", any()) } returns guests
        every { parkingLinkPort.findActiveSlotByGuestId(any(), any()) } returns null
    }

    @Test
    fun `이름 매칭 1건이면 SEARCH_STATE_ONE을 반환한다`() {
        stubSearch(listOf(sampleGuestListItem("g1", "김진우"), sampleGuestListItem("g2", "박서연")))

        val result = service.lookup("ev1", "김진우")

        assertEquals(GuestListResult.SEARCH_STATE_ONE, result.searchState)
        assertEquals(1, result.total)
    }

    @Test
    fun `이름 부분일치 매칭 2건 이상이면 SEARCH_STATE_MANY를 반환한다`() {
        stubSearch(listOf(sampleGuestListItem("g1", "김진우"), sampleGuestListItem("g2", "김진호")))

        val result = service.lookup("ev1", "김진")

        assertEquals(GuestListResult.SEARCH_STATE_MANY, result.searchState)
        assertEquals(2, result.total)
    }

    @Test
    fun `매칭 0건이면 SEARCH_STATE_NONE과 빈 items를 반환한다`() {
        stubSearch(listOf(sampleGuestListItem("g1", "김진우")))

        val result = service.lookup("ev1", "없는이름")

        assertEquals(GuestListResult.SEARCH_STATE_NONE, result.searchState)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `차량 뒷자리가 일치하면 이름이 q와 무관해도 매칭된다`() {
        // plate에 하이픈이 섞여 있어도 숫자만 추출해 뒷자리 endsWith가 성립해야 한다.
        stubSearch(listOf(sampleGuestListItem("g1", "김진우", plate = "12가-3456")))

        val result = service.lookup("ev1", "3456")

        assertEquals(1, result.total)
        assertEquals("g1", result.items.single().guest.id)
    }

    @Test
    fun `순수 한글 q는 숫자가 0개라 plate 매칭 분기가 발동하지 않는다(빈 문자열 오매칭 방지 회귀)`() {
        // 가드 누락 시 digitsOnly(plate).endsWith("")가 항상 true가 되어 아래 guest도 오매칭된다.
        stubSearch(listOf(sampleGuestListItem("g1", "박서연", plate = "12가3456")))

        val result = service.lookup("ev1", "김진우")

        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `guestPort search는 취소자를 제외하도록 includeCancelled false·paging false로 호출된다`() {
        val query = slot<GuestSearchQuery>()
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { guestPort.search("ev1", capture(query)) } returns emptyList()

        service.lookup("ev1", "김진우")

        assertEquals(false, query.captured.includeCancelled)
        assertEquals(false, query.captured.paging)
    }

    @Test
    fun `blank q는 IllegalArgumentException을 던진다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit

        assertFailsWith<IllegalArgumentException> { service.lookup("ev1", "") }
        assertFailsWith<IllegalArgumentException> { service.lookup("ev1", "   ") }
    }

    @Test
    fun `조회 시 eventScopeGuard assertAccess가 매 호출마다 실행된다`() {
        stubSearch(listOf(sampleGuestListItem("g1", "김진우")))

        service.lookup("ev1", "김진우")

        verify(exactly = 1) { eventScopeGuard.assertAccess("ev1") }
    }

    @Test
    fun `매칭 guest 중 활성 주차 슬롯이 없으면 parking은 null이다`() {
        stubSearch(listOf(sampleGuestListItem("g1", "김진우")))

        val result = service.lookup("ev1", "김진우")

        assertNull(result.items.single().parking)
    }

    @Test
    fun `매칭 guest에 활성 주차 슬롯이 있으면 구분자를 정규화해 병기한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { guestPort.search("ev1", any()) } returns listOf(sampleGuestListItem("g1", "김진우"))
        every { parkingLinkPort.findActiveSlotByGuestId("ev1", "g1") } returns ParkingSlotRef("지하 2층·A구역·3")

        val result = service.lookup("ev1", "김진우")

        val parking = result.items.single().parking
        assertEquals("지하 2층·A구역·3", parking?.slotSig)
        assertEquals("지하 2층 A구역 3", parking?.display)
    }
}
