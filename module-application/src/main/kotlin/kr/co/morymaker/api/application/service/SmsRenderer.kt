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

    /**
     * 토큰 전체를 alternation으로 결합한 단일 정규식 — `render`가 원본 본문을 한 번만
     * 스캔하도록 한다. 매 호출마다 재컴파일하지 않도록 object 레벨 상수로 둔다.
     * `Regex.escape`로 토큰의 `[`·`]`·`$` 등 정규식 메타문자를 이스케이프한다. 6종 토큰은
     * 서로 접두사 관계가 아니므로 alternation 순서는 매칭 결과에 영향을 주지 않는다.
     */
    private val TOKEN_PATTERN = Regex(VARIABLES.joinToString("|") { Regex.escape(it) })

    private val EVENT_DATE_ZONE = ZoneId.of("Asia/Seoul")
    private val EVENT_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * QR링크 토큰은 `{eventBaseUrl}/u/{token}` 풀 URL(스킴 포함)로 치환된다 — `PublicHubResponse`
     * 조립 SSOT와 동일 규칙. null 필드(소속·장소·일시)는 빈 문자열로 치환한다.
     *
     * ⚠️ 템플릿 본문에서 이 토큰을 참조할 때는 앞에 별도 스킴을 붙이지 않는다 — 이미 풀
     * URL이므로 스킴을 덧붙이면 이중 스킴이 된다.
     *
     * 단일 패스 치환 — 원본 본문에서 발견한 토큰 위치만 값으로 바꾸고, 치환된 결과 문자열은
     * 다시 스캔하지 않는다. 참석자 데이터(이름·소속 등)에 토큰과 동일한 문자열이 우연히
     * 들어 있어도 2차 치환되지 않는다.
     */
    fun render(body: String, guest: GuestListItem, event: Event, eventBaseUrl: String): String {
        val qrLink = "$eventBaseUrl/u/${guest.token}"
        val eventDate = event.eventDate?.atZone(EVENT_DATE_ZONE)?.format(EVENT_DATE_FORMATTER) ?: ""
        val values = mapOf(
            "[\$참석자]" to guest.name,
            "[\$소속]" to (guest.org ?: ""),
            "[\$행사명]" to event.name,
            "[\$장소]" to (event.place ?: ""),
            "[\$일시]" to eventDate,
            "[\$QR링크]" to qrLink,
        )
        return TOKEN_PATTERN.replace(body) { match -> values[match.value] ?: match.value }
    }
}
