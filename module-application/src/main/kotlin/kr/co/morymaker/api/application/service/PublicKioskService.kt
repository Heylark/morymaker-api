package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.CheckinResult
import kr.co.morymaker.api.application.port.`in`.CheckinTarget
import kr.co.morymaker.api.application.port.`in`.LookupResult
import kr.co.morymaker.api.application.port.`in`.PublicKioskUseCase
import kr.co.morymaker.api.application.port.out.EventPort
import kr.co.morymaker.api.application.port.out.ParkingRecordPort
import kr.co.morymaker.api.application.port.out.RecordSearchQuery
import kr.co.morymaker.api.domain.event.Event
import kr.co.morymaker.api.domain.parking.ParkingRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * [PublicKioskUseCase] 구현체(REQ-0019, KIO-02·04·05) — 무인증 공개 경로다.
 *
 * 생성자에 `EventScopeGuard`가 없다 — [PublicOnsiteService]·[PublicParkingService]와 동일한
 * 구조적 설계로, 행사 스코프 게이트를 호출할 수단 자체가 없다. eid(=event.id를 그대로 재사용)의
 * 존재·상태만으로 인가를 대체한다: 무효 eid는 [NoSuchElementException](404), 종료 행사는
 * [EventNotOpenException](409)으로 거부한다(D-I, `PublicOnsiteService.fetchOpenEvent` 원리 재사용
 * — idle-contents의 fail-open과 달리 참석자 PII를 다루므로 보수적 정책).
 *
 * 이름검색·체크인은 각각 [LookupSearchSupport]·[CheckinSupport](가드-free 코어, SCN 인증 경로와
 * 공유하는 SSOT)에 위임한다(D-E). 주차검색은 신규 SQL 없이 기존 [ParkingRecordPort.search]를
 * 직접 재사용한다(D-G — 이름검색과 근본 도메인이 달라 lookup 통합 대신 전용 분기, 단
 * 컨트롤러·서비스는 단일 클래스로 응집).
 */
@Service
internal class PublicKioskService(
    private val eventPort: EventPort,
    private val lookupSearchSupport: LookupSearchSupport,
    private val checkinSupport: CheckinSupport,
    private val recordPort: ParkingRecordPort,
) : PublicKioskUseCase {

    @Transactional(readOnly = true)
    override fun searchAttendees(eventId: String, name: String): LookupResult {
        fetchOpenEvent(eventId)
        val trimmed = name.trim()
        // 단일 문자 대량 열거 차단(D-C) — 부분일치 자체는 코어(LookupSearchSupport) 그대로 유지.
        require(trimmed.length >= MIN_NAME_LENGTH) { "이름은 ${MIN_NAME_LENGTH}자 이상 입력해 주세요" }
        return lookupSearchSupport.search(eventId, trimmed)
    }

    @Transactional
    override fun checkin(eventId: String, guestId: String): CheckinResult {
        fetchOpenEvent(eventId)
        return checkinSupport.checkin(eventId, CheckinTarget.ByGuestId(guestId))
    }

    @Transactional(readOnly = true)
    override fun searchParking(eventId: String, plateTail: String): List<ParkingRecord> {
        fetchOpenEvent(eventId)
        val trimmed = plateTail.trim()
        require(PLATE_TAIL_REGEX.matches(trimmed)) { "차량번호 뒷자리 4자리 숫자를 입력해 주세요" }
        return recordPort.search(
            eventId,
            RecordSearchQuery(plateTail = trimmed, status = ParkingRecord.STATUS_PARKED),
        )
    }

    /** eid(=event.id) 존재 확인 + status 게이트(종료만 거부) — 3 엔드포인트 공통(D-I). */
    private fun fetchOpenEvent(eventId: String): Event {
        val event = eventPort.fetch(eventId) ?: throw NoSuchElementException("행사를 찾을 수 없습니다")
        if (event.status == Event.STATUS_CLOSED) {
            throw EventNotOpenException("종료된 행사입니다")
        }
        return event
    }

    companion object {
        private const val MIN_NAME_LENGTH = 2
        private val PLATE_TAIL_REGEX = Regex("^\\d{4}$")
    }
}
