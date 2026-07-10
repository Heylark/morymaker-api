package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.co.morymaker.api.application.port.out.EventPort
import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.toGuest
import kr.co.morymaker.api.domain.event.Event
import kr.co.morymaker.api.domain.guest.Guest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

/**
 * [PublicHubService] 단위 테스트(§10-1·§10-2) — 무효 token 거부(404)·capability 기반 인가
 * 대체·[GuestWriteSupport] 위임을 검증한다.
 */
class PublicHubServiceTest {

    private val guestPort = mockk<GuestPort>()
    private val eventPort = mockk<EventPort>()
    private val guestWriteSupport = mockk<GuestWriteSupport>()
    private val service = PublicHubService(guestPort, eventPort, guestWriteSupport)

    private fun sampleGuestListItem(
        eventId: String = "ev1",
        plate: String? = "78다9012",
        token: String = "t-token",
    ) = GuestListItem(
        id = "g1",
        eventId = eventId,
        name = "이서연",
        org = "□□협회",
        title = "회장",
        phone = null,
        plate = plate,
        seatGroupId = null,
        status = Guest.STATUS_ATTENDED,
        src = Guest.SRC_PRE,
        visitAt = null,
        token = token,
        createdAt = Instant.now(),
        seatLabel = "1번 테이블",
    )

    private fun sampleEvent(id: String = "ev1") = Event(
        id = id,
        name = "2026 신년 VIP 만찬",
        eventDate = null,
        place = "그랜드볼룸",
        type = null,
        status = Event.STATUS_PREPARING,
        active = true,
        bgColor = "#0c1322",
        pointColor = "#c9a24a",
        titleColor = null,
        bodyColor = null,
        kv = null,
        defaultIdleMode = null,
        smsPolicy = null,
        createdAt = Instant.now(),
    )

    @Test
    fun `getHub는 유효 token이면 guest와 event를 함께 반환한다`() {
        every { guestPort.findByToken("t-token") } returns sampleGuestListItem()
        every { eventPort.fetch("ev1") } returns sampleEvent()

        val result = service.getHub("t-token")

        assertEquals("이서연", result.guest.name)
        assertEquals("2026 신년 VIP 만찬", result.event.name)
    }

    @Test
    fun `getHub는 무효 token이면 404로 이어지는 NoSuchElementException을 던진다`() {
        every { guestPort.findByToken("invalid") } returns null

        assertFailsWith<NoSuchElementException> { service.getHub("invalid") }
    }

    @Test
    fun `updatePrereg는 GuestWriteSupport backfillPlate에 위임한 뒤 최신 허브를 재조회한다`() {
        val guest = sampleGuestListItem(plate = null)
        every { guestPort.findByToken("t-token") } returns guest
        every { guestWriteSupport.backfillPlate("ev1", any(), "12가3456") } returns guest.toGuest()
        every { eventPort.fetch("ev1") } returns sampleEvent()

        val result = service.updatePrereg("t-token", "12가3456")

        assertEquals("이서연", result.guest.name)
        verify(exactly = 1) { guestWriteSupport.backfillPlate("ev1", any(), "12가3456") }
    }

    @Test
    fun `updatePrereg는 무효 token이면 backfillPlate를 호출하지 않고 404로 이어지는 예외를 던진다`() {
        every { guestPort.findByToken("invalid") } returns null

        assertFailsWith<NoSuchElementException> { service.updatePrereg("invalid", "12가3456") }
        verify(exactly = 0) { guestWriteSupport.backfillPlate(any(), any(), any()) }
    }

    @Test
    fun `PublicHubService는 EventScopeGuard에 의존하지 않는다(구조적으로 assertAccess 호출 불가)`() {
        val constructorParamTypes = PublicHubService::class.primaryConstructor
            ?.parameters
            ?.map { it.type.toString() }
            .orEmpty()

        assertFalse(
            constructorParamTypes.any { it.contains("EventScopeGuard") },
            "PublicHubService 생성자에 EventScopeGuard가 있으면 안 된다(무인증 공개 경로): $constructorParamTypes",
        )
    }
}
