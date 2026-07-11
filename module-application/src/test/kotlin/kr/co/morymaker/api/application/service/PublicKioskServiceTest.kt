package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.co.morymaker.api.application.port.`in`.CheckinResult
import kr.co.morymaker.api.application.port.`in`.CheckinTarget
import kr.co.morymaker.api.application.port.`in`.GuestListResult
import kr.co.morymaker.api.application.port.`in`.LookupResult
import kr.co.morymaker.api.application.port.out.EventPort
import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.application.port.out.ParkingRecordPort
import kr.co.morymaker.api.application.port.out.RecordSearchQuery
import kr.co.morymaker.api.domain.event.Event
import kr.co.morymaker.api.domain.guest.Guest
import kr.co.morymaker.api.domain.parking.ParkingRecord
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [PublicKioskService] 단위 테스트 — eid capability 게이트(D-I)·검색 게이트(D-C)·guard-free
 * 코어([LookupSearchSupport]·[CheckinSupport]·[ParkingRecordPort]) 위임을 검증한다. 코어
 * 자체의 매칭·상태전이 로직은 각 코어 전용 테스트(`LookupSearchSupportTest`·`CheckinSupportTest`)
 * 가 단일 검증 지점 — 여기서는 게이트·위임 경계만 확인한다.
 */
class PublicKioskServiceTest {

    private val eventPort = mockk<EventPort>()
    private val lookupSearchSupport = mockk<LookupSearchSupport>()
    private val checkinSupport = mockk<CheckinSupport>()
    private val recordPort = mockk<ParkingRecordPort>()
    private val service = PublicKioskService(eventPort, lookupSearchSupport, checkinSupport, recordPort)

    private fun sampleEvent(status: String = Event.STATUS_PREPARING) = Event(
        id = "ev1", name = "테스트 행사", eventDate = null, place = null, type = null, status = status,
        active = true, bgColor = null, pointColor = null, titleColor = null, bodyColor = null, kv = null,
        smsPolicy = null, createdAt = Instant.now(),
    )

    // ── eid capability 게이트(D-I) — 3 엔드포인트 공통 ──────────────────

    @Test
    fun `존재하지 않는 eid는 NoSuchElementException(404)을 던진다`() {
        every { eventPort.fetch("ghost") } returns null

        assertFailsWith<NoSuchElementException> { service.searchAttendees("ghost", "김진우") }
        assertFailsWith<NoSuchElementException> { service.checkin("ghost", "g1") }
        assertFailsWith<NoSuchElementException> { service.searchParking("ghost", "1234") }
    }

    @Test
    fun `종료된 행사는 EventNotOpenException(409)을 던진다`() {
        every { eventPort.fetch("ev1") } returns sampleEvent(status = Event.STATUS_CLOSED)

        assertFailsWith<EventNotOpenException> { service.searchAttendees("ev1", "김진우") }
        assertFailsWith<EventNotOpenException> { service.checkin("ev1", "g1") }
        assertFailsWith<EventNotOpenException> { service.searchParking("ev1", "1234") }
    }

    // ── searchAttendees(D-C 검색 게이트) ─────────────────────────────

    @Test
    fun `이름이 2자 미만이면 IllegalArgumentException을 던진다(단일문자 열거 차단)`() {
        every { eventPort.fetch("ev1") } returns sampleEvent()

        assertFailsWith<IllegalArgumentException> { service.searchAttendees("ev1", "김") }
        assertFailsWith<IllegalArgumentException> { service.searchAttendees("ev1", " ") }
    }

    @Test
    fun `이름이 2자 이상이면 LookupSearchSupport에 위임하고 결과를 그대로 반환한다`() {
        every { eventPort.fetch("ev1") } returns sampleEvent()
        val expected = LookupResult(items = emptyList(), total = 0, searchState = GuestListResult.SEARCH_STATE_NONE)
        every { lookupSearchSupport.search("ev1", "김진우") } returns expected

        val result = service.searchAttendees("ev1", "  김진우  ")

        assertEquals(expected, result)
        verify(exactly = 1) { lookupSearchSupport.search("ev1", "김진우") }
    }

    // ── checkin(D-D·F) ────────────────────────────────────────────

    @Test
    fun `checkin은 guestId를 CheckinTarget ByGuestId로 변환해 CheckinSupport에 위임한다`() {
        every { eventPort.fetch("ev1") } returns sampleEvent()
        val guest = GuestListItem(
            id = "g1", eventId = "ev1", name = "김진우", org = null, title = null, phone = null,
            plate = null, seatGroupId = null, status = Guest.STATUS_ATTENDED, src = Guest.SRC_PRE,
            visitAt = Instant.now(), token = "tok", createdAt = Instant.now(), seatLabel = null,
        )
        val expected = CheckinResult(CheckinResult.CHECKED_IN, guest, null)
        val target = slot<CheckinTarget>()
        every { checkinSupport.checkin("ev1", capture(target)) } returns expected

        val result = service.checkin("ev1", "g1")

        assertEquals(expected, result)
        assertEquals(CheckinTarget.ByGuestId("g1"), target.captured)
    }

    // ── searchParking(D-C 검색 게이트) ────────────────────────────────

    @Test
    fun `plateTail이 4자리 숫자가 아니면 IllegalArgumentException을 던진다`() {
        every { eventPort.fetch("ev1") } returns sampleEvent()

        assertFailsWith<IllegalArgumentException> { service.searchParking("ev1", "123") }
        assertFailsWith<IllegalArgumentException> { service.searchParking("ev1", "12345") }
        assertFailsWith<IllegalArgumentException> { service.searchParking("ev1", "12가3") }
        assertFailsWith<IllegalArgumentException> { service.searchParking("ev1", "") }
    }

    @Test
    fun `plateTail이 4자리 숫자면 활성(주차중) 기록만 조회하도록 위임한다`() {
        every { eventPort.fetch("ev1") } returns sampleEvent()
        val query = slot<RecordSearchQuery>()
        every { recordPort.search("ev1", capture(query)) } returns emptyList()

        service.searchParking("ev1", "1234")

        assertEquals("1234", query.captured.plateTail)
        assertEquals(ParkingRecord.STATUS_PARKED, query.captured.status)
    }
}
