package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.domain.event.Event
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 문자 템플릿 치환 순수함수(§7) — gid로 이미 해결된 guest/event를 받아 변수 토큰 6종을
 * 치환한다. 이름 문자열 매칭은 하지 않는다 — 호출부가 항상 gid로 확정한 [GuestListItem]을
 * 넘기므로 동명이인 오발송이 설계상 원천 불가하다.
 *
 * Spring 무의존(object + 순수함수) — module-application 서비스 레이어에서만 호출한다.
 */
object SmsRenderer {

    /** spec 7-1 변수 토큰 6종 — 7-1 응답 `variables` 및 치환 대상의 단일 소스. */
    val VARIABLES = listOf("[\$참석자]", "[\$소속]", "[\$행사명]", "[\$장소]", "[\$일시]", "[\$QR링크]")

    private val EVENT_DATE_ZONE = ZoneId.of("Asia/Seoul")
    private val EVENT_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * QR링크 토큰은 `{eventBaseUrl}/u/{token}` 풀 URL(스킴 포함)로 치환된다 — `PublicHubResponse`
     * 조립 SSOT와 동일 규칙. null 필드(소속·장소·일시)는 빈 문자열로 치환한다.
     *
     * ⚠️ 템플릿 본문에서 이 토큰을 참조할 때는 앞에 별도 스킴을 붙이지 않는다 — 이미 풀
     * URL이므로 스킴을 덧붙이면 이중 스킴이 된다.
     */
    fun render(body: String, guest: GuestListItem, event: Event, eventBaseUrl: String): String {
        val qrLink = "$eventBaseUrl/u/${guest.token}"
        val eventDate = event.eventDate?.atZone(EVENT_DATE_ZONE)?.format(EVENT_DATE_FORMATTER) ?: ""
        return body
            .replace("[\$참석자]", guest.name)
            .replace("[\$소속]", guest.org ?: "")
            .replace("[\$행사명]", event.name)
            .replace("[\$장소]", event.place ?: "")
            .replace("[\$일시]", eventDate)
            .replace("[\$QR링크]", qrLink)
    }
}
