package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.parking.SlotOccupiedException
import kr.co.morymaker.api.application.port.`in`.MappingResult
import kr.co.morymaker.api.application.port.`in`.ParkingRecordUseCase
import kr.co.morymaker.api.application.port.`in`.RecordListQuery
import kr.co.morymaker.api.application.port.`in`.RegisterParkingCommand
import kr.co.morymaker.api.application.port.`in`.RegisterParkingResult
import kr.co.morymaker.api.application.port.`in`.SupersededInfo
import kr.co.morymaker.api.application.port.out.GuestLinkPort
import kr.co.morymaker.api.application.port.out.ParkingRecordPort
import kr.co.morymaker.api.application.port.out.RecordSearchQuery
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.parking.ParkingRecord
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * [ParkingRecordUseCase] 구현체 — 등록 코어(§6-6, 무결성 3-5 승계 3분기 + 매핑 3-7)와
 * 목록·출차·승계 확인 해제(§6-5·6-7·6-8)를 담당한다.
 *
 * 헥사고날 레이어: application(service). `internal`: api-app은 [ParkingRecordUseCase]
 * 인터페이스만 의존한다.
 *
 * [register]는 `@Transactional`(REQUIRED) 안에서 [ParkingRecordPort.selectActiveBySlot]로
 * 대상 자리 활성 기록을 조회해 케이스를 분기한다(02-architect §4-1 결정적 우선순위 표) — 이 조회는
 * 잠금을 걸지 않는다. 동시 경쟁 방어는 active_key UNIQUE 제약 단독이 담당하며, 위반 시
 * [guardingSlotUniqueness]가 409로 번역한다. self-call·REQUIRES_NEW 없이 이 메서드 하나가
 * 트랜잭션 경계를 이룬다(kotlin-conventions §1-4).
 */
@Service
internal class ParkingRecordService(
    private val recordPort: ParkingRecordPort,
    private val guestLinkPort: GuestLinkPort,
    private val eventScopeGuard: EventScopeGuard,
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
        require(
            command.registeredBy == ParkingRecord.REGISTERED_BY_SELF ||
                command.registeredBy == ParkingRecord.REGISTERED_BY_STAFF,
        ) { "registeredBy는 셀프 또는 요원만 가능합니다" }

        val normPlate = normalizePlate(command.plate)
        // 대상 자리 활성 기록 조회(P1, v2) — 동시 경쟁은 active_key UNIQUE가 최종 방어.
        val targetActive = recordPort.selectActiveBySlot(eventId, command.slotSig)
        val plateActive = recordPort.selectActiveByPlate(eventId, normPlate)

        val outcome = when {
            // 케이스 A — 본인 재등록(동일 자리): 대상 점유자가 요청 차량 본인.
            targetActive != null && targetActive.plate == normPlate -> {
                recordPort.touchRegisteredAt(targetActive.id)
                val touched = recordPort.fetchById(eventId, targetActive.id) ?: targetActive
                Outcome(RegisterParkingResult.RESULT_RE_REGISTERED, touched, superseded = null)
            }
            // 케이스 B — 승계(타 차량, 신규): 대상 점유, 요청 차량은 다른 활성 기록 없음.
            targetActive != null && plateActive == null -> {
                recordPort.checkout(targetActive.id)
                val newRecord = buildNewRecord(eventId, command, normPlate, reviewNeeded = true)
                guardingSlotUniqueness { recordPort.insert(newRecord) }
                val superseded = targetActive.with(status = ParkingRecord.STATUS_CHECKED_OUT)
                Outcome(RegisterParkingResult.RESULT_SUPERSEDED, newRecord, superseded)
            }
            // 케이스 C — 승계+이동(타 차량, 요청 차량이 다른 자리에 이미 활성): 대상 출차 + 내 기록 이동.
            targetActive != null -> {
                recordPort.checkout(targetActive.id)
                val moved = plateActive!!.with(slotSig = command.slotSig, zoneId = command.zoneId, reviewNeeded = true)
                guardingSlotUniqueness { recordPort.updateSlotMove(moved) }
                val superseded = targetActive.with(status = ParkingRecord.STATUS_CHECKED_OUT)
                Outcome(RegisterParkingResult.RESULT_SUPERSEDED, moved, superseded)
            }
            // 케이스 D — 자리 이동(빈 대상, 요청 차량이 다른 자리에 이미 활성).
            plateActive != null -> {
                val moved = plateActive.with(slotSig = command.slotSig, zoneId = command.zoneId)
                guardingSlotUniqueness { recordPort.updateSlotMove(moved) }
                Outcome(RegisterParkingResult.RESULT_RE_REGISTERED, moved, superseded = null)
            }
            // 케이스 E — 신규 주차(빈 대상, 요청 차량 미주차). 동시 최초 주차는 UNIQUE가 최종 방어.
            else -> {
                val newRecord = buildNewRecord(eventId, command, normPlate, reviewNeeded = false)
                guardingSlotUniqueness { recordPort.insert(newRecord) }
                Outcome(RegisterParkingResult.RESULT_PARKED, newRecord, superseded = null)
            }
        }

        val mapping = mapGuestForRecord(eventId, outcome.record, command.phone)
        return RegisterParkingResult(
            result = outcome.result,
            record = outcome.record,
            mapping = mapping,
            supersededRecord = outcome.superseded?.let { SupersededInfo(it.id, it.status, "자동 출차(후속 등록)") },
            message = messageFor(outcome.result),
        )
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

    // active_key(자리 유일성) 경쟁 실패를 자리 점유(409)로 번역한다. insert(B·E)와
    // 자리 이동 update(C·D) 모두 같은 UNIQUE로 직렬화되므로 동일 가드로 감싼다.
    private inline fun <T> guardingSlotUniqueness(block: () -> T): T =
        try {
            block()
        } catch (e: DuplicateKeyException) { // 선착이 이미 자리 점유(UNIQUE 위반)
            throw SlotOccupiedException()
        } catch (e: PessimisticLockingFailureException) { // 고동시성 락 경합(중복키 insert 데드락·락 timeout)
            throw SlotOccupiedException()
        }

    private fun buildNewRecord(
        eventId: String,
        command: RegisterParkingCommand,
        normPlate: String,
        reviewNeeded: Boolean,
    ): ParkingRecord = ParkingRecord(
        id = UUID.randomUUID().toString(),
        eventId = eventId,
        zoneId = command.zoneId,
        slotSig = command.slotSig,
        plate = normPlate,
        phone = command.phone,
        vipName = command.vipName,
        guestId = null,
        registeredBy = command.registeredBy,
        registeredAt = Instant.now(),
        status = ParkingRecord.STATUS_PARKED,
        reviewNeeded = reviewNeeded,
    )

    /**
     * 매핑(3-7, parking→guest) — 등록 성공 직후 항상 시도한다. 매칭 실패(활성 참석자 없음)는
     * 정상 상태이며 아무 것도 갱신하지 않는다.
     */
    private fun mapGuestForRecord(eventId: String, record: ParkingRecord, phone: String?): MappingResult {
        val match = guestLinkPort.findGuestByPlateOrPhone(eventId, record.plate, phone) ?: return MappingResult(matched = false)
        recordPort.linkGuest(record.id, match.guestId)
        guestLinkPort.markVisitedAndBackfillPlate(match.guestId, record.plate)
        val guestStatus = if (match.guestStatus == "대기") "방문" else match.guestStatus
        return MappingResult(matched = true, guestId = match.guestId, guestName = match.guestName, guestStatus = guestStatus)
    }

    private data class Outcome(val result: String, val record: ParkingRecord, val superseded: ParkingRecord?)

    companion object {
        private fun normalizePlate(plate: String): String = plate.replace(" ", "").trim()

        private fun messageFor(result: String): String? = when (result) {
            RegisterParkingResult.RESULT_SUPERSEDED -> "기존 등록은 자동 출차 처리되고, 현장 요원이 확인합니다"
            RegisterParkingResult.RESULT_RE_REGISTERED -> "본인 재등록 — 위치 갱신"
            else -> null
        }
    }
}
