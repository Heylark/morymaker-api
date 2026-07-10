package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.co.morymaker.api.application.port.`in`.SmsSendCommand
import kr.co.morymaker.api.application.port.out.EventPort
import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.SmsLogPort
import kr.co.morymaker.api.application.port.out.SmsSendResult
import kr.co.morymaker.api.application.port.out.SmsSenderPort
import kr.co.morymaker.api.application.port.out.SmsTemplatePort
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.event.Event
import kr.co.morymaker.api.domain.sms.SmsLog
import kr.co.morymaker.api.domain.sms.SmsTemplate
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SmsService] 단위 테스트 — 포트 전부를 mock으로 대체해 게이트 집합연산·발송 오케스트레이션
 * (부분 성공 정책)·confirm 게이트·초대 1회 excludeAlreadySent 로직만 검증한다.
 *
 * 실 DB·mapper·[SmsRenderer] 세부 치환 로직은 각각 [SmsRendererTest]·Tester 종합 TC가 담당한다.
 */
class SmsServiceTest {

    private val smsTemplatePort = mockk<SmsTemplatePort>()
    private val smsLogPort = mockk<SmsLogPort>()
    private val smsSenderPort = mockk<SmsSenderPort>()
    private val guestPort = mockk<GuestPort>()
    private val eventPort = mockk<EventPort>()
    private val eventScopeGuard = mockk<EventScopeGuard>()
    private val service = SmsService(smsTemplatePort, smsLogPort, smsSenderPort, guestPort, eventPort, eventScopeGuard)

    private fun sampleGuest(
        id: String = "g1",
        name: String = "김민준",
        org: String? = "○○그룹",
        phone: String? = "010-1234-5678",
        token: String = "t$id",
    ) = GuestListItem(
        id = id, eventId = "ev1", name = name, org = org, title = null, phone = phone,
        plate = null, seatGroupId = null, status = "대기", src = "사전", visitAt = null,
        token = token, createdAt = Instant.now(), seatLabel = null,
    )

    private fun sampleEvent() = Event(
        id = "ev1", name = "2026 신년 VIP 만찬", eventDate = Instant.parse("2026-01-10T01:00:00Z"),
        place = "그랜드볼룸", type = null, status = "준비", active = false,
        bgColor = null, pointColor = null, titleColor = null, bodyColor = null, kv = null,
        smsPolicy = null, createdAt = Instant.now(),
    )

    private fun sampleTemplate(body: String = "[\$참석자]님 안내드립니다. [\$QR링크]") = SmsTemplate(
        id = "tmpl1", eventId = "ev1", body = body, updatedAt = Instant.parse("2026-01-08T00:00:00Z"),
    )

    // ── getTemplate / upsertTemplate ──────────────────────────────

    @Test
    fun `getTemplate은 설정 전이면 빈 본문과 null updatedAt을 반환한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { smsTemplatePort.fetchByEvent("ev1") } returns null

        val result = service.getTemplate("ev1")

        assertEquals("", result.body)
        assertNull(result.updatedAt)
        assertEquals(SmsRenderer.VARIABLES, result.variables)
    }

    @Test
    fun `upsertTemplate은 upsert 후 재조회한 값을 반환한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { smsTemplatePort.upsert(any()) } returns Unit
        every { smsTemplatePort.fetchByEvent("ev1") } returns sampleTemplate(body = "새 본문")

        val result = service.upsertTemplate("ev1", "새 본문")

        assertEquals("새 본문", result.body)
        verify(exactly = 1) { smsTemplatePort.upsert(any()) }
    }

    // ── gate ───────────────────────────────────────────────────────

    @Test
    fun `gate는 전화번호 누락 guest를 blocked로, 나머지를 candidates로 분류한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { smsTemplatePort.fetchByEvent("ev1") } returns sampleTemplate()
        every { guestPort.search("ev1", any()) } returns listOf(
            sampleGuest(id = "g1", phone = "010-1111-2222"),
            sampleGuest(id = "g2", phone = null),
        )
        every { smsLogPort.selectSentGuestIds("ev1") } returns emptyList()
        every { eventPort.fetch("ev1") } returns sampleEvent()

        val result = service.gate("ev1", excludeAlreadySent = true, eventBaseUrl = "https://event.morymaker.co.kr")

        assertEquals(1, result.candidates)
        assertEquals(1, result.blocked.size)
        assertEquals(listOf("전화번호"), result.blocked.first().missing)
        assertEquals(false, result.canSend)
        assertEquals(0, result.alreadySent)
    }

    @Test
    fun `gate는 excludeAlreadySent=true면 이미 발송한 gid를 후보에서 제외하되 alreadySent 수는 전체 기준으로 센다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { smsTemplatePort.fetchByEvent("ev1") } returns sampleTemplate()
        every { guestPort.search("ev1", any()) } returns listOf(sampleGuest(id = "g1"), sampleGuest(id = "g2"))
        every { smsLogPort.selectSentGuestIds("ev1") } returns listOf("g1")
        every { eventPort.fetch("ev1") } returns sampleEvent()

        val result = service.gate("ev1", excludeAlreadySent = true, eventBaseUrl = "https://event.morymaker.co.kr")

        assertEquals(1, result.candidates) // g1 제외, g2만 후보
        assertTrue(result.canSend)
        assertEquals(1, result.alreadySent) // g1은 후보에서 빠졌지만 이미 발송 카운트는 유지
    }

    // ── send ───────────────────────────────────────────────────────

    @Test
    fun `send는 confirm이 false면 IllegalArgumentException을 던진다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit

        assertFailsWith<IllegalArgumentException> {
            service.send("ev1", SmsSendCommand(excludeAlreadySent = true, confirm = false), "https://event.morymaker.co.kr")
        }
    }

    @Test
    fun `send는 게이트에 누락자가 있으면 SmsSendBlockedException을 던진다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { guestPort.search("ev1", any()) } returns listOf(sampleGuest(id = "g1", phone = null))
        every { smsLogPort.selectSentGuestIds("ev1") } returns emptyList()

        assertFailsWith<SmsSendBlockedException> {
            service.send("ev1", SmsSendCommand(excludeAlreadySent = true, confirm = true), "https://event.morymaker.co.kr")
        }
    }

    @Test
    fun `send는 유효 대상 전원에게 렌더링·발송·로그기록을 수행하고 성공 건수를 집계한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { guestPort.search("ev1", any()) } returns listOf(sampleGuest(id = "g1"), sampleGuest(id = "g2"))
        every { smsLogPort.selectSentGuestIds("ev1") } returns emptyList()
        every { smsTemplatePort.fetchByEvent("ev1") } returns sampleTemplate()
        every { eventPort.fetch("ev1") } returns sampleEvent()
        every { smsSenderPort.send(any(), any()) } returns SmsSendResult(status = SmsLog.STATUS_SUCCESS)
        val insertedLogs = mutableListOf<SmsLog>()
        every { smsLogPort.insert(capture(insertedLogs)) } returns Unit

        val result =
            service.send("ev1", SmsSendCommand(excludeAlreadySent = true, confirm = true), "https://event.morymaker.co.kr")

        assertEquals(2, result.sent)
        assertEquals(0, result.failed)
        assertEquals(2, insertedLogs.size)
        assertTrue(insertedLogs.all { it.status == SmsLog.STATUS_SUCCESS && it.bodySnapshot != null })
        verify(exactly = 2) { smsSenderPort.send(any(), any()) }
    }

    @Test
    fun `send는 개별 발송 실패를 흡수해 status=실패로 기록하고 나머지 대상 처리를 계속한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { guestPort.search("ev1", any()) } returns listOf(sampleGuest(id = "g1"), sampleGuest(id = "g2"))
        every { smsLogPort.selectSentGuestIds("ev1") } returns emptyList()
        every { smsTemplatePort.fetchByEvent("ev1") } returns sampleTemplate()
        every { eventPort.fetch("ev1") } returns sampleEvent()
        every { smsSenderPort.send("010-1234-5678", any()) } throws RuntimeException("네트워크 오류")
        val insertedLogs = mutableListOf<SmsLog>()
        every { smsLogPort.insert(capture(insertedLogs)) } returns Unit

        val result =
            service.send("ev1", SmsSendCommand(excludeAlreadySent = true, confirm = true), "https://event.morymaker.co.kr")

        assertEquals(0, result.sent)
        assertEquals(2, result.failed)
        assertTrue(insertedLogs.all { it.status == SmsLog.STATUS_FAILED })
    }

    // ── resend ─────────────────────────────────────────────────────

    @Test
    fun `resend는 confirm이 false면 IllegalArgumentException을 던진다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit

        assertFailsWith<IllegalArgumentException> {
            service.resend("ev1", "g1", confirm = false, eventBaseUrl = "https://event.morymaker.co.kr")
        }
    }

    @Test
    fun `resend는 이미 발송 이력이 있어도 게이트 확인 없이 신규 로그를 남긴다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { guestPort.fetchDetailById("ev1", "g1") } returns sampleGuest(id = "g1")
        every { smsTemplatePort.fetchByEvent("ev1") } returns sampleTemplate()
        every { eventPort.fetch("ev1") } returns sampleEvent()
        every { smsSenderPort.send(any(), any()) } returns SmsSendResult(status = SmsLog.STATUS_SUCCESS)
        every { smsLogPort.insert(any()) } returns Unit

        val result = service.resend("ev1", "g1", confirm = true, eventBaseUrl = "https://event.morymaker.co.kr")

        assertEquals(SmsLog.STATUS_SUCCESS, result.status)
        verify(exactly = 0) { smsLogPort.selectSentGuestIds(any()) }
        verify(exactly = 1) { smsLogPort.insert(any()) }
    }

    // ── listLog ────────────────────────────────────────────────────

    @Test
    fun `listLog는 sentAt을 KST ISO-8601 오프셋 문자열로 변환한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val storedLog = SmsLog(
            id = "log1", eventId = "ev1", guestId = "g1", nameSnapshot = "김민준(○○그룹)",
            phone = "010-1234-5678", sentAt = Instant.parse("2026-01-10T01:02:00Z"),
            status = SmsLog.STATUS_SUCCESS, bodySnapshot = "본문",
        )
        every { smsLogPort.selectByEvent("ev1") } returns listOf(storedLog)

        val result = service.listLog("ev1")

        assertEquals(1, result.size)
        assertEquals("2026-01-10T10:02:00+09:00", result.first().sentAt)
    }
}
