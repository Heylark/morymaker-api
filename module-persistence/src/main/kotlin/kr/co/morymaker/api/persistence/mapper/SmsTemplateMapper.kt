package kr.co.morymaker.api.persistence.mapper

import kr.co.morymaker.api.domain.sms.SmsTemplate
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * 문자 템플릿 전용 MyBatis 매퍼 인터페이스(§7, 신설) — sms_template 단일 테이블.
 *
 * 헥사고날 레이어: persistence(mapper) — DB 관심사만. 컬럼명 기반 명시 매핑(positional index
 * 금지 — GuestMapper·StatsMapper와 동일 원칙).
 *
 * XML 정의: resources/mapper/sms/SmsTemplateMapper.xml
 */
@Mapper
interface SmsTemplateMapper {

    /** 단건 조회(event_id UNIQUE). 없으면 null. */
    fun fetchByEvent(@Param("eventId") eventId: String): SmsTemplate?

    /** upsert 예외(mybatis-advanced.md) — event_id UNIQUE ON DUPLICATE KEY UPDATE. */
    fun upsert(template: SmsTemplate)
}
