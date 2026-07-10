package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kr.co.morymaker.api.application.port.`in`.CreateEventCommand
import kr.co.morymaker.api.application.port.`in`.UpdateBrandingCommand
import kr.co.morymaker.api.application.port.`in`.UpdateEventCommand
import kr.co.morymaker.api.application.port.out.EventPort
import kr.co.morymaker.api.application.security.EventAccessDeniedException
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.event.Event
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        bgColor = "#0c1322",
        pointColor = "#c9a24a",
        titleColor = "#ffffff",
        bodyColor = "#d9d9d9",
        kv = "기존 KV",
        defaultIdleMode = "branded",
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
        assertNull(result.defaultIdleMode)
        assertTrue(result.id.isNotBlank())
        assertEquals(result.id, inserted.captured.id)
        verify(exactly = 1) { eventPort.insert(any()) }
    }

    // ── updateEvent(§2-4) — 저장 게이트 회귀 검증 ─────────────────────

    @Test
    fun `updateEvent는 일반 필드만 병합해 update 포트로 위임하고 브랜딩 필드는 기존 값을 그대로 보존한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { eventPort.fetch("ev1") } returns sampleEvent("ev1")
        val updated = io.mockk.slot<Event>()
        every { eventPort.update(capture(updated)) } returns Unit

        val command = UpdateEventCommand(
            name = "수정된 행사명",
            eventDate = null,
            place = "새 장소",
            type = "연회식",
            kv = "새 KV",
            status = "운영중",
            active = true,
        )
        val result = service.updateEvent("ev1", command)

        assertEquals("수정된 행사명", result.name)
        assertEquals("새 장소", result.place)
        assertEquals("새 KV", result.kv)
        // 브랜딩 필드는 §2-4 UpdateEventCommand에 필드 자체가 없어 기존 값이 그대로 유지된다(ADR-001 게이트).
        assertEquals("#0c1322", result.bgColor)
        assertEquals("#c9a24a", result.pointColor)
        assertEquals("#ffffff", result.titleColor)
        assertEquals("#d9d9d9", result.bodyColor)
        assertEquals("branded", result.defaultIdleMode)
        assertEquals("수정된 행사명", updated.captured.name)
        verify(exactly = 1) { eventPort.update(any()) }
        verify(exactly = 0) { eventPort.updateBranding(any()) }
    }

    @Test
    fun `updateEvent는 존재하지 않는 행사면 NoSuchElementException을 던진다`() {
        every { eventScopeGuard.assertAccess("ghost") } returns Unit
        every { eventPort.fetch("ghost") } returns null

        val command = UpdateEventCommand(
            name = "무엇", eventDate = null, place = null, type = null,
            kv = null, status = "준비", active = false,
        )
        assertFailsWith<NoSuchElementException> { service.updateEvent("ghost", command) }
    }

    // ── updateBranding(§11-1) — 명시 저장 게이트 검증 ──────────────────

    @Test
    fun `updateBranding은 컬러4종·kv·defaultIdleMode만 병합하고 일반 필드는 기존 값을 그대로 보존한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { eventPort.fetch("ev1") } returns sampleEvent("ev1")
        val updated = io.mockk.slot<Event>()
        every { eventPort.updateBranding(capture(updated)) } returns Unit

        val command = UpdateBrandingCommand(
            bgColor = "#111111",
            pointColor = "#222222",
            titleColor = "#333333",
            bodyColor = "#444444",
            kv = "브랜딩 KV",
            defaultIdleMode = "fullbleed",
        )
        val result = service.updateBranding("ev1", command)

        assertEquals("#111111", result.bgColor)
        assertEquals("#222222", result.pointColor)
        assertEquals("#333333", result.titleColor)
        assertEquals("#444444", result.bodyColor)
        assertEquals("fullbleed", result.defaultIdleMode)
        // 일반 필드는 UpdateBrandingCommand에 필드 자체가 없어 기존 값이 그대로 유지된다.
        assertEquals("샘플 행사", result.name)
        assertEquals("운영중", result.status)
        assertEquals(updated.captured.bgColor, result.bgColor)
        verify(exactly = 1) { eventPort.updateBranding(any()) }
        verify(exactly = 0) { eventPort.update(any()) }
    }

    @Test
    fun `updateBranding은 존재하지 않는 행사면 NoSuchElementException을 던진다`() {
        every { eventScopeGuard.assertAccess("ghost") } returns Unit
        every { eventPort.fetch("ghost") } returns null

        val command = UpdateBrandingCommand(
            bgColor = null, pointColor = null, titleColor = null, bodyColor = null,
            kv = null, defaultIdleMode = null,
        )
        assertFailsWith<NoSuchElementException> { service.updateBranding("ghost", command) }
    }

    private fun slotCapture() = io.mockk.slot<Event>()
}
