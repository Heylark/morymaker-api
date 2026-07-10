package kr.co.morymaker.api.application.port.out

import kr.co.morymaker.api.domain.sms.SmsTemplate

/**
 * 문자 템플릿 영속 포트-out(§7) — module-persistence의 `SmsTemplatePersistenceAdapter`가
 * 구현한다.
 */
interface SmsTemplatePort {

    /** 단건 조회(event_id UNIQUE). 없으면 null(행사에 템플릿이 아직 설정되지 않음). */
    fun fetchByEvent(eventId: String): SmsTemplate?

    /** event_id UNIQUE upsert(ON DUPLICATE KEY UPDATE — TOCTOU 없음, 원자적). */
    fun upsert(template: SmsTemplate)
}
