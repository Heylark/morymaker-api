package kr.co.morymaker.api.domain.sms

import java.time.Instant

/**
 * 문자 발송 이력 도메인 엔티티 — 발송 시점 이름·본문 스냅샷을 보존해 "무엇을 보냈나"를 나중에
 * 재현한다(§7-6).
 *
 * Event.kt·Guest.kt와 동일 원칙: 일반 class + id 기반 equals/hashCode(data class 아님).
 *
 * @param id 로그 PK(UUID)
 * @param eventId 소속 행사 PK
 * @param guestId 참석자 FK — ON DELETE SET NULL(참석자 행이 삭제돼도 발송 이력 자체는 보존)
 * @param nameSnapshot 발송 시점 이름(+소속) 스냅샷 — 예 "김민준(○○그룹)", 소속 없으면 이름만
 * @param phone 발송 시점 전화번호
 * @param sentAt 발송 시각
 * @param status `성공`/`실패`/`반송`
 * @param bodySnapshot 발송 시점 치환 결과 실본문
 */
class SmsLog(
    val id: String,
    val eventId: String,
    val guestId: String?,
    val nameSnapshot: String?,
    val phone: String?,
    val sentAt: Instant,
    val status: String,
    val bodySnapshot: String?,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SmsLog) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "SmsLog(id=$id, eventId=$eventId, status=$status)"

    companion object {
        // 스텁 발송사가 항상 반환하는 값 + 향후 실 발송사가 채울 수 있는 나머지 두 상태.
        const val STATUS_SUCCESS = "성공"
        const val STATUS_FAILED = "실패"
        const val STATUS_BOUNCED = "반송"
    }
}
