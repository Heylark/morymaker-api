package kr.co.morymaker.api.domain.sms

import java.time.Instant

/**
 * 초대 문자 템플릿 도메인 엔티티 — 행사당 단일 본문(event_id UNIQUE, 멀티 슬롯 없음).
 *
 * Event.kt·Guest.kt와 동일 원칙: 일반 class + id 기반 equals/hashCode(data class 아님).
 *
 * @param id 템플릿 PK(UUID)
 * @param eventId 소속 행사 PK — event_id UNIQUE(행사당 1건)
 * @param body 템플릿 본문(변수 토큰 6종 — 참석자·소속·행사명·장소·일시·QR링크 — 포함 가능)
 * @param updatedAt 최종 수정 시각
 */
class SmsTemplate(
    val id: String,
    val eventId: String,
    val body: String,
    val updatedAt: Instant,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SmsTemplate) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "SmsTemplate(id=$id, eventId=$eventId)"
}
