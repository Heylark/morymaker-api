package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.domain.event.Event
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * [SmsRenderer] 단위 테스트 — 순수함수 치환 로직만 검증한다(포트 의존 없음).
 */
class SmsRendererTest {

    private fun sampleGuest(name: String = "김민준", org: String? = "○○그룹", token: String = "t1000") = GuestListItem(
        id = "g1", eventId = "ev1", name = name, org = org, title = null, phone = "010-1234-5678",
        plate = null, seatGroupId = null, status = "대기", src = "사전", visitAt = null,
        token = token, createdAt = Instant.now(), seatLabel = null,
    )

    private fun sampleEvent(
        name: String = "2026 신년 VIP 만찬",
        place: String? = "그랜드볼룸",
        eventDate: Instant? = Instant.parse("2026-01-10T01:00:00Z"), // KST 10:00
    ) = Event(
        id = "ev1", name = name, eventDate = eventDate, place = place, type = null,
        status = "준비", active = false, bgColor = null, pointColor = null, titleColor = null,
        bodyColor = null, kv = null, defaultIdleMode = null, smsPolicy = null, createdAt = Instant.now(),
    )

    @Test
    fun `render는 6종 토큰을 모두 gid로 확정된 guest·event 값으로 치환한다`() {
        val body = "[\$참석자]님, [\$행사명]에 초대드립니다. 장소: [\$장소] / 일시: [\$일시] / 링크: [\$QR링크]"

        val rendered = SmsRenderer.render(body, sampleGuest(), sampleEvent(), "https://event.morymaker.co.kr")

        assertEquals(
            "김민준님, 2026 신년 VIP 만찬에 초대드립니다. 장소: 그랜드볼룸 / 일시: 2026-01-10 10:00 / 링크: https://event.morymaker.co.kr/u/t1000",
            rendered,
        )
    }

    @Test
    fun `render는 소속·장소·일시가 null이면 빈 문자열로 치환한다(null 문자열 노출 없음)`() {
        val body = "[\$참석자]([\$소속]) 장소:[\$장소] 일시:[\$일시]"
        val guest = sampleGuest(org = null)
        val event = sampleEvent(place = null, eventDate = null)

        val rendered = SmsRenderer.render(body, guest, event, "https://event.morymaker.co.kr")

        assertEquals("김민준() 장소: 일시:", rendered)
        assertFalse(rendered.contains("null"))
    }

    @Test
    fun `QR링크는 스킴 포함 풀 URL로 치환되어 템플릿 쪽 스킴과 이중으로 겹치지 않는다`() {
        val rendered = SmsRenderer.render(
            "[\$QR링크]", sampleGuest(token = "abc123"), sampleEvent(), "https://event.morymaker.co.kr",
        )

        assertEquals("https://event.morymaker.co.kr/u/abc123", rendered)
        assertFalse(rendered.startsWith("https://https://"))
    }

    @Test
    fun `VARIABLES는 spec 6종 토큰을 순서대로 노출한다`() {
        assertEquals(
            listOf("[\$참석자]", "[\$소속]", "[\$행사명]", "[\$장소]", "[\$일시]", "[\$QR링크]"),
            SmsRenderer.VARIABLES,
        )
    }

    @Test
    fun `render는 참석자 데이터에 박힌 토큰을 2차 치환하지 않는다`() {
        val body = "[\$참석자]님, 링크: [\$QR링크]"
        val guest = sampleGuest(name = "홍길동[\$QR링크]", token = "zzz999")

        val rendered = SmsRenderer.render(body, guest, sampleEvent(), "https://event.morymaker.co.kr")

        assertEquals("홍길동[\$QR링크]님, 링크: https://event.morymaker.co.kr/u/zzz999", rendered)
    }
}
