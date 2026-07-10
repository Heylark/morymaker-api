package kr.co.morymaker.api.persistence.mapper

import kr.co.morymaker.api.domain.sms.SmsLog
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * 문자 발송 이력 전용 MyBatis 매퍼 인터페이스(§7, 신설) — sms_log 단일 테이블만 조회한다.
 * guest JOIN 금지(동결 준수, 02-architect M5) — 게이트 alreadySent 판정은 서비스 레이어가
 * [selectSentGuestIds] 결과와 GuestMapper 조회 결과를 집합 연산으로 조합한다.
 *
 * 헥사고날 레이어: persistence(mapper) — DB 관심사만. 컬럼명 기반 명시 매핑(positional index
 * 금지 — GuestMapper·StatsMapper와 동일 원칙).
 *
 * XML 정의: resources/mapper/sms/SmsLogMapper.xml
 */
@Mapper
interface SmsLogMapper {

    fun insert(log: SmsLog)

    /** 발송 완료(gid) 집합 — 게이트 alreadySent/excludeAlreadySent 판정용. */
    fun selectSentGuestIds(@Param("eventId") eventId: String): List<String>

    /** 발송 이력(§7-6, sent_at DESC). */
    fun selectByEvent(@Param("eventId") eventId: String): List<SmsLog>

    /** 참석자 취소 연동 삭제(§4-4 회수) — hard delete(sms_log 소프트삭제 컬럼 없음). */
    fun deleteByGuest(@Param("eventId") eventId: String, @Param("guestId") guestId: String)
}
