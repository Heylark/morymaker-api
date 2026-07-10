package kr.co.morymaker.api.persistence.adapter.sms

import kr.co.morymaker.api.application.port.out.SmsTemplatePort
import kr.co.morymaker.api.domain.sms.SmsTemplate
import kr.co.morymaker.api.persistence.mapper.SmsTemplateMapper
import org.springframework.stereotype.Component

/**
 * [SmsTemplatePort] 구현체 — thin delegate. 헥사고날 레이어: Persistence(adapter). `internal`:
 * application 계층은 [SmsTemplatePort] 인터페이스만 의존한다.
 */
@Component
internal class SmsTemplatePersistenceAdapter(
    private val smsTemplateMapper: SmsTemplateMapper,
) : SmsTemplatePort {

    override fun fetchByEvent(eventId: String): SmsTemplate? = smsTemplateMapper.fetchByEvent(eventId)

    override fun upsert(template: SmsTemplate) = smsTemplateMapper.upsert(template)
}
