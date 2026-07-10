package kr.co.morymaker.api.persistence.adapter.sms

import kr.co.morymaker.api.application.port.out.SmsLogPort
import kr.co.morymaker.api.domain.sms.SmsLog
import kr.co.morymaker.api.persistence.mapper.SmsLogMapper
import org.springframework.stereotype.Component

/**
 * [SmsLogPort] 구현체 — thin delegate. 헥사고날 레이어: Persistence(adapter). `internal`:
 * application 계층은 [SmsLogPort] 인터페이스만 의존한다.
 */
@Component
internal class SmsLogPersistenceAdapter(
    private val smsLogMapper: SmsLogMapper,
) : SmsLogPort {

    override fun insert(log: SmsLog) = smsLogMapper.insert(log)

    override fun selectSentGuestIds(eventId: String): List<String> = smsLogMapper.selectSentGuestIds(eventId)

    override fun selectByEvent(eventId: String): List<SmsLog> = smsLogMapper.selectByEvent(eventId)

    override fun deleteByGuest(eventId: String, guestId: String) = smsLogMapper.deleteByGuest(eventId, guestId)
}
