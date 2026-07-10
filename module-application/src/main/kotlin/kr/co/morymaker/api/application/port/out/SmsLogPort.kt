package kr.co.morymaker.api.application.port.out

import kr.co.morymaker.api.domain.sms.SmsLog

/**
 * 문자 발송 이력 영속 포트-out(§7) — module-persistence의 `SmsLogPersistenceAdapter`가
 * 구현한다. sms_log 단일 테이블만 다룬다 — guest JOIN은 서비스 레이어 집합 연산으로 대체한다
 * (02-architect M5, 동결 준수).
 */
interface SmsLogPort {

    fun insert(log: SmsLog)

    /** 발송 완료(gid) 집합 — 게이트 alreadySent/excludeAlreadySent 판정용. */
    fun selectSentGuestIds(eventId: String): List<String>

    /** 발송 이력(§7-6, sent_at DESC). */
    fun selectByEvent(eventId: String): List<SmsLog>

    /** 참석자 취소 연동 삭제(§4-4 회수, GuestService.cancelGuest에서 호출) — hard delete. */
    fun deleteByGuest(eventId: String, guestId: String)
}
