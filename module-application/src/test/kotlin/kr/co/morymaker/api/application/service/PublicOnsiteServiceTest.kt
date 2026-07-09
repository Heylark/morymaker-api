package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.co.morymaker.api.application.port.`in`.OnsiteRegisterCommand
import kr.co.morymaker.api.application.port.`in`.RegisterGuestCommand
import kr.co.morymaker.api.application.port.out.EventPort
import kr.co.morymaker.api.domain.event.Event
import kr.co.morymaker.api.domain.guest.Guest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * [PublicOnsiteService] 단위 테스트(§10-5·§10-6) — eventCode(=event.id) 존재·status 게이트(D5)·
 * [GuestWriteSupport] 위임을 검증한다.
 */
class PublicOnsiteServiceTest {

    private val eventPort = mockk<EventPort>()
    private val guestWriteSupport = mockk<GuestWriteSupport>()
    private val service = PublicOnsiteService(eventPort, guestWriteSupport)

    private fun sampleEvent(status: String = Event.STATUS_PREPARING) = Event(
        id = "ev1",
        name = "2026 신년 VIP 만찬",
        eventDate = null,
        place = null,
        type = null,
        status = status,
        active = true,
        bgColor = "#0c1322",
        pointColor = "#c9a24a",
        titleColor = null,
        bodyColor = null,
        kv = null,
        smsPolicy = null,
        createdAt = Instant.now(),
    )

    private fun sampleGuest() = Guest(
        id = "g1", eventId = "ev1", name = "홍길동", org = null, title = null, phone = null,
        plate = null, seatGroupId = null, status = Guest.STATUS_WAITING, src = Guest.SRC_ONSITE,
        visitAt = null, token = "t1020", createdAt = Instant.now(),
    )

    // ── getOnsiteForm(§10-5) ───────────────────────────────────────

    @Test
    fun `getOnsiteForm은 준비 상태 행사를 그대로 반환한다`() {
        every { eventPort.fetch("ev1") } returns sampleEvent(status = Event.STATUS_PREPARING)

        val result = service.getOnsiteForm("ev1")

        assertEquals("2026 신년 VIP 만찬", result.name)
    }

    @Test
    fun `getOnsiteForm은 무효 eventCode면 404로 이어지는 예외를 던진다`() {
        every { eventPort.fetch("invalid") } returns null

        assertFailsWith<NoSuchElementException> { service.getOnsiteForm("invalid") }
    }

    @Test
    fun `getOnsiteForm은 종료된 행사면 409로 이어지는 EventNotOpenException을 던진다`() {
        every { eventPort.fetch("ev1") } returns sampleEvent(status = Event.STATUS_CLOSED)

        assertFailsWith<EventNotOpenException> { service.getOnsiteForm("ev1") }
    }

    // ── registerOnsite(§10-6) ──────────────────────────────────────

    @Test
    fun `registerOnsite는 현장 등록 커맨드로 GuestWriteSupport createGuest에 위임한다`() {
        // '운영중'은 Event.kt에 상수가 없다(스펙 §2 문서화만) — 준비·운영중 모두 허용 대상임을
        // 확인하기 위해 STATUS_PREPARING이 아닌 값으로 명시 테스트한다.
        every { eventPort.fetch("ev1") } returns sampleEvent(status = "운영중")
        val commandSlot = slot<RegisterGuestCommand>()
        every { guestWriteSupport.createGuest("ev1", capture(commandSlot)) } returns sampleGuest()

        val result = service.registerOnsite(
            "ev1",
            OnsiteRegisterCommand(name = "홍길동", org = "○○사", phone = "010-1111-2222", plate = "12가3456"),
        )

        assertEquals("t1020", result.token)
        assertEquals("홍길동", commandSlot.captured.name)
        assertEquals(Guest.SRC_ONSITE, commandSlot.captured.src)
        assertEquals(null, commandSlot.captured.seatGroupId)
        assertEquals(null, commandSlot.captured.title)
    }

    @Test
    fun `registerOnsite는 종료된 행사면 GuestWriteSupport를 호출하지 않고 409로 이어지는 예외를 던진다`() {
        every { eventPort.fetch("ev1") } returns sampleEvent(status = Event.STATUS_CLOSED)

        assertFailsWith<EventNotOpenException> {
            service.registerOnsite("ev1", OnsiteRegisterCommand(name = "홍길동", org = null, phone = null, plate = null))
        }
        verify(exactly = 0) { guestWriteSupport.createGuest(any(), any()) }
    }

    @Test
    fun `PublicOnsiteService는 EventScopeGuard에 의존하지 않는다(구조적으로 assertAccess 호출 불가)`() {
        val constructorParamTypes = PublicOnsiteService::class.primaryConstructor
            ?.parameters
            ?.map { it.type.toString() }
            .orEmpty()

        assertFalse(
            constructorParamTypes.any { it.contains("EventScopeGuard") },
            "PublicOnsiteService 생성자에 EventScopeGuard가 있으면 안 된다(무인증 공개 경로): $constructorParamTypes",
        )
    }

}
