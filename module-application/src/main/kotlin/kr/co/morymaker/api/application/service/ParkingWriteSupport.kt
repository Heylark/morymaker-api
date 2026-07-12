package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.parking.SlotOccupiedException
import kr.co.morymaker.api.application.port.`in`.MappingResult
import kr.co.morymaker.api.application.port.`in`.RegisterParkingCommand
import kr.co.morymaker.api.application.port.`in`.RegisterParkingResult
import kr.co.morymaker.api.application.port.`in`.SupersededInfo
import kr.co.morymaker.api.application.port.out.GuestLinkPort
import kr.co.morymaker.api.application.port.out.ParkingRecordPort
import kr.co.morymaker.api.domain.parking.ParkingRecord
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 가드-free 주차 등록 코어(SSOT) — 인증 경로([ParkingRecordService])와 공개 경로
 * ([PublicParkingService])가 공유하는 무결성 3-5 승계 3분기(케이스 A~E) + 매핑 3-7 로직을 한
 * 곳에 모은다([GuestWriteSupport] 정확한 선례).
 *
 * `EventScopeGuard`를 의도적으로 의존하지 않는다 — 이 클래스는 인가를 판단하지 않고 쓰기만
 * 수행하며, 인가는 항상 호출자 책임이다(인증 경로는 [ParkingRecordService]가 `assertAccess`를
 * 먼저 호출한 뒤에만 이 클래스를 호출하고, 공개 경로는 slotCode capability 유효성 검증으로
 * 인가를 대체한 뒤에만 호출한다). 생성자에 `EventScopeGuard` 참조 자체가 없으므로 이 클래스
 * 내부에서는 구조적으로 그 가드를 호출할 수 없다.
 */
@Service
internal class ParkingWriteSupport(
    private val recordPort: ParkingRecordPort,
    private val guestLinkPort: GuestLinkPort,
) {

    @Transactional
    fun register(eventId: String, command: RegisterParkingCommand): RegisterParkingResult {
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
                recordPort.touchRegisteredAt(eventId, targetActive.id)
                val touched = recordPort.fetchById(eventId, targetActive.id) ?: targetActive
                Outcome(RegisterParkingResult.RESULT_RE_REGISTERED, touched, superseded = null)
            }
            // 케이스 B — 승계(타 차량, 신규): 대상 점유, 요청 차량은 다른 활성 기록 없음.
            targetActive != null && plateActive == null -> {
                recordPort.checkout(eventId, targetActive.id)
                val newRecord = buildNewRecord(eventId, command, normPlate, reviewNeeded = true)
                guardingSlotUniqueness { recordPort.insert(newRecord) }
                val superseded = targetActive.with(status = ParkingRecord.STATUS_CHECKED_OUT)
                Outcome(RegisterParkingResult.RESULT_SUPERSEDED, newRecord, superseded)
            }
            // 케이스 C — 승계+이동(타 차량, 요청 차량이 다른 자리에 이미 활성): 대상 출차 + 내 기록 이동.
            targetActive != null -> {
                recordPort.checkout(eventId, targetActive.id)
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
        recordPort.linkGuest(eventId, record.id, match.guestId)
        guestLinkPort.markVisitedAndBackfillPlate(eventId, match.guestId, record.plate)
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
