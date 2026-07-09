package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kr.co.morymaker.api.application.port.`in`.CreateEventCommand
import kr.co.morymaker.api.application.port.out.EventPort
import kr.co.morymaker.api.application.security.EventAccessDeniedException
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.event.Event
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [EventService] 단위 테스트 — [EventPort]/[EventScopeGuard]를 mock으로 대체해 유스케이스
 * 오케스트레이션(스코프 게이트 호출 순서·결과 필터링·생성 기본값)만 검증한다.
 */
class EventServiceTest {

    private val eventPort = mockk<EventPort>()
    private val eventScopeGuard = mockk<EventScopeGuard>()
    private val service = EventService(eventPort, eventScopeGuard)

    private fun sampleEvent(id: String = "ev1") = Event(
        id = id,
        name = "샘플 행사",
        eventDate = null,
        place = null,
        type = null,
        status = "운영중",
        active = true,
        bgColor = null,
        pointColor = null,
        titleColor = null,
        bodyColor = null,
        kv = null,
        smsPolicy = null,
        createdAt = Instant.now(),
    )

    @Test
    fun `listEvents는 SYSTEM_ADMIN이면 필터 없이 조회한다`() {
        every { eventScopeGuard.currentScopeOrNull() } returns null
        every { eventPort.search(null) } returns listOf(sampleEvent())

        val result = service.listEvents()

        assertEquals(1, result.size)
        verify(exactly = 1) { eventPort.search(null) }
    }

    @Test
    fun `listEvents는 EVENT_ADMIN이면 담당 행사로 필터링해 조회한다`() {
        every { eventScopeGuard.currentScopeOrNull() } returns listOf("ev1", "ev2")
        every { eventPort.search(listOf("ev1", "ev2")) } returns listOf(sampleEvent("ev1"))

        val result = service.listEvents()

        assertEquals(listOf("ev1"), result.map { it.id })
        verify(exactly = 1) { eventPort.search(listOf("ev1", "ev2")) }
    }

    @Test
    fun `getEvent는 Layer2b assertAccess를 fetch보다 먼저 호출한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { eventPort.fetch("ev1") } returns sampleEvent("ev1")

        val result = service.getEvent("ev1")

        assertEquals("ev1", result.id)
        verifyOrder {
            eventScopeGuard.assertAccess("ev1")
            eventPort.fetch("ev1")
        }
    }

    @Test
    fun `getEvent는 스코프 거부 시 fetch를 호출하지 않고 예외를 그대로 전파한다`() {
        every { eventScopeGuard.assertAccess("ev9") } throws EventAccessDeniedException("ev9")

        assertFailsWith<EventAccessDeniedException> { service.getEvent("ev9") }
        verify(exactly = 0) { eventPort.fetch(any()) }
    }

    @Test
    fun `getEvent는 스코프는 통과했지만 존재하지 않으면 NoSuchElementException을 던진다`() {
        every { eventScopeGuard.assertAccess("ghost") } returns Unit
        every { eventPort.fetch("ghost") } returns null

        assertFailsWith<NoSuchElementException> { service.getEvent("ghost") }
    }

    @Test
    fun `createEvent는 상태 준비·active false·기본 문자 정책으로 생성하고 새 UUID를 발급한다`() {
        val inserted = slotCapture()
        every { eventPort.insert(capture(inserted)) } returns Unit

        val command = CreateEventCommand(
            name = "창립 30주년 기념식",
            eventDate = null,
            place = "아트센터 대극장",
            type = "극장식",
            bgColor = "#14241a",
            pointColor = "#2f8f5b",
            titleColor = "#ffffff",
            bodyColor = "#d9d9d9",
            kv = "30th ANNIVERSARY",
        )

        val result = service.createEvent(command)

        assertEquals(Event.STATUS_PREPARING, result.status)
        assertFalse(result.active)
        assertEquals(Event.DEFAULT_SMS_POLICY, result.smsPolicy)
        assertEquals("창립 30주년 기념식", result.name)
        assertTrue(result.id.isNotBlank())
        assertEquals(result.id, inserted.captured.id)
        verify(exactly = 1) { eventPort.insert(any()) }
    }

    private fun slotCapture() = io.mockk.slot<Event>()
}
