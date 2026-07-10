package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.ParkingRecordUseCase
import kr.co.morymaker.api.application.port.`in`.RecordListQuery
import kr.co.morymaker.api.application.port.`in`.RegisterParkingCommand
import kr.co.morymaker.api.application.port.`in`.RegisterParkingResult
import kr.co.morymaker.api.application.port.out.ParkingRecordPort
import kr.co.morymaker.api.application.port.out.RecordSearchQuery
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.parking.ParkingRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * [ParkingRecordUseCase] 구현체 — 인증 요원 경로(목록·출차·승계 확인 해제(§6-5·6-7·6-8) +
 * 등록(§6-6) 진입점)를 담당한다.
 *
 * 헥사고날 레이어: application(service). `internal`: api-app은 [ParkingRecordUseCase]
 * 인터페이스만 의존한다.
 *
 * [register]는 행사 스코프 검증([EventScopeGuard.assertAccess])만 수행하고, 실제 등록 코어
 * (무결성 3-5 승계 3분기 + 매핑 3-7)는 [ParkingWriteSupport](가드-free, 공개 셀프 주차 경로와
 * 공유하는 SSOT)에 위임한다 — 인증 여부만 다른 두 진입점이 동일한 등록 로직을 재사용한다.
 * `@Transactional`은 assertAccess와 위임 호출을 한 트랜잭션 경계로 묶어 리팩터 이전과 동일한
 * 원자성을 보존한다(self-call·REQUIRES_NEW 없음 — kotlin-conventions §1-4 준수, 별도 빈 호출이라
 * 프록시가 정상 적용된다).
 */
@Service
internal class ParkingRecordService(
    private val recordPort: ParkingRecordPort,
    private val eventScopeGuard: EventScopeGuard,
    private val parkingWriteSupport: ParkingWriteSupport,
) : ParkingRecordUseCase {

    @Transactional(readOnly = true)
    override fun listRecords(eventId: String, query: RecordListQuery): List<ParkingRecord> {
        eventScopeGuard.assertAccess(eventId)
        return recordPort.search(
            eventId,
            RecordSearchQuery(
                zoneId = query.zoneId,
                status = query.status,
                plateTail = query.plateTail,
                reviewNeeded = query.reviewNeeded,
            ),
        )
    }

    @Transactional
    override fun register(eventId: String, command: RegisterParkingCommand): RegisterParkingResult {
        eventScopeGuard.assertAccess(eventId)
        return parkingWriteSupport.register(eventId, command)
    }

    @Transactional
    override fun checkout(eventId: String, id: String): ParkingRecord {
        eventScopeGuard.assertAccess(eventId)
        val existing = recordPort.fetchById(eventId, id) ?: throw NoSuchElementException("주차 기록을 찾을 수 없습니다")
        // 이미 출차 상태면 재변경 없이 멱등 재조회(§6-7).
        if (existing.status == ParkingRecord.STATUS_CHECKED_OUT) return existing
        recordPort.checkout(id)
        return existing.with(status = ParkingRecord.STATUS_CHECKED_OUT)
    }

    @Transactional
    override fun clearReview(eventId: String, id: String): ParkingRecord {
        eventScopeGuard.assertAccess(eventId)
        val existing = recordPort.fetchById(eventId, id) ?: throw NoSuchElementException("주차 기록을 찾을 수 없습니다")
        recordPort.clearReview(id)
        return existing.with(reviewNeeded = false)
    }
}
