package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.co.morymaker.api.application.port.`in`.CheckinResult
import kr.co.morymaker.api.application.port.`in`.CheckinTarget
import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.guest.Guest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/**
 * [CheckinService] 단위 테스트 — [EventScopeGuard]/[CheckinSupport] 위임 가드만 검증한다.
 * KIO 경로는 이 REQ 범위 밖(D2 이연)이라 대상 아님.
 *
 * 상태 전이·멱등성·동시성 방어 자체 검증은 가드-free 추출 이후 [CheckinSupportTest]로
 * 이전됐다 — 공개 kiosk 경로와 공유하는 SSOT라 그쪽이 두 경로 공통 동작의 단일 검증 지점이다.
 */
class CheckinServiceTest {

    private val eventScopeGuard = mockk<EventScopeGuard>()
    private val checkinSupport = mockk<CheckinSupport>()
    private val service = CheckinService(checkinSupport, eventScopeGuard)

    private fun sampleGuestListItem() = GuestListItem(
        id = "g1", eventId = "ev1", name = "김진우", org = null, title = null, phone = null,
        plate = null, seatGroupId = null, status = Guest.STATUS_ATTENDED, src = Guest.SRC_PRE,
        visitAt = Instant.now(), token = "sample-token", createdAt = Instant.now(), seatLabel = "A-12",
    )

    @Test
    fun `checkin은 assertAccess 호출 후 CheckinSupport에 위임하고 결과를 그대로 반환한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val expected = CheckinResult(CheckinResult.CHECKED_IN, sampleGuestListItem(), null)
        every { checkinSupport.checkin("ev1", CheckinTarget.ByToken("tok1")) } returns expected

        val result = service.checkin("ev1", CheckinTarget.ByToken("tok1"))

        assertEquals(expected, result)
        verify(exactly = 1) { eventScopeGuard.assertAccess("ev1") }
        verify(exactly = 1) { checkinSupport.checkin("ev1", CheckinTarget.ByToken("tok1")) }
    }

    @Test
    fun `scanPreview는 assertAccess 호출 후 CheckinSupport에 위임한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val expected = sampleGuestListItem()
        every { checkinSupport.scanPreview("ev1", "tok1") } returns expected

        val result = service.scanPreview("ev1", "tok1")

        assertEquals(expected, result)
        verify(exactly = 1) { eventScopeGuard.assertAccess("ev1") }
    }

    @Test
    fun `cancelCheckin은 assertAccess 호출 후 CheckinSupport에 위임한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val expected = Guest(
            id = "g1", eventId = "ev1", name = "김진우", org = null, title = null, phone = null,
            plate = null, seatGroupId = null, status = Guest.STATUS_WAITING, src = Guest.SRC_PRE,
            visitAt = null, token = "sample-token", createdAt = Instant.now(),
        )
        every { checkinSupport.cancelCheckin("ev1", "g1") } returns expected

        val result = service.cancelCheckin("ev1", "g1")

        assertEquals(expected, result)
        verify(exactly = 1) { eventScopeGuard.assertAccess("ev1") }
    }
}
