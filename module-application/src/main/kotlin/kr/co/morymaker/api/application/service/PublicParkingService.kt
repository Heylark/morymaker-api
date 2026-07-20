package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.PublicParkingUseCase
import kr.co.morymaker.api.application.port.`in`.PublicSlotView
import kr.co.morymaker.api.application.port.`in`.RegisterParkingCommand
import kr.co.morymaker.api.application.port.`in`.RegisterParkingResult
import kr.co.morymaker.api.application.port.`in`.SelfParkCommand
import kr.co.morymaker.api.application.port.out.EventPort
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.ParkingRecordPort
import kr.co.morymaker.api.application.port.out.ParkingZonePort
import kr.co.morymaker.api.domain.parking.ParkingRecord
import kr.co.morymaker.api.domain.parking.ParkingSlot
import kr.co.morymaker.api.domain.parking.ParkingZone
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * [PublicParkingUseCase] 구현체(§10-3·§10-4) — 무인증 공개 경로다.
 *
 * 생성자에 `EventScopeGuard`가 없다 — [PublicOnsiteService]와 동일한 구조적 설계로, 행사 스코프
 * 게이트를 호출할 수단 자체가 없다. slotCode 파싱·구획 존재·자리 범위 검증만으로 인가를
 * 대체한다(무효면 404, enumeration-safe). 등록은 [ParkingWriteSupport](가드-free 코어, 인증
 * 경로와 공유)에 위임하고 `registeredBy`는 항상 셀프로 고정한다.
 */
@Service
internal class PublicParkingService(
    private val zonePort: ParkingZonePort,
    private val recordPort: ParkingRecordPort,
    private val eventPort: EventPort,
    private val guestPort: GuestPort,
    private val parkingWriteSupport: ParkingWriteSupport,
) : PublicParkingUseCase {

    @Transactional(readOnly = true)
    override fun getSlotView(slotCode: String): PublicSlotView {
        val (zone, slotNo) = resolveSlot(slotCode)
        val event = eventPort.fetch(zone.eventId) ?: throw NoSuchElementException("행사를 찾을 수 없습니다")
        val slotSig = ParkingSlot.slotSig(zone.part1, zone.part2, zone.part3, zone.part4, slotNo)
        val occupied = recordPort.selectActiveBySlot(zone.eventId, slotSig) != null
        return PublicSlotView(
            slotCode = slotCode,
            slotSig = slotSig,
            display = ParkingSlot.slotDisplay(slotSig),
            viewType = if (occupied) PublicSlotView.VIEW_TYPE_OCCUPIED_NOTICE else PublicSlotView.VIEW_TYPE_SELF_PARK_FORM,
            occupied = occupied,
            event = event,
        )
    }

    @Transactional
    override fun selfPark(slotCode: String, command: SelfParkCommand): RegisterParkingResult {
        val (zone, slotNo) = resolveSlot(slotCode)
        val slotSig = ParkingSlot.slotSig(zone.part1, zone.part2, zone.part3, zone.part4, slotNo)
        val prefilled = prefillFromToken(command)
        val registerCommand = RegisterParkingCommand(
            slotSig = slotSig,
            zoneId = zone.id,
            plate = prefilled.plate,
            phone = prefilled.phone,
            vipName = prefilled.vipName,
            registeredBy = ParkingRecord.REGISTERED_BY_SELF,
        )
        return parkingWriteSupport.register(zone.eventId, registerCommand)
    }

    /**
     * slotCode → (구획, 자리번호) 역파싱 + 존재·범위 검증 공통(GET·POST 양쪽). eventId는
     * slotCode에 없으므로 조회된 구획에서 파생한다. 실패는 전부 404(enumeration-safe).
     */
    private fun resolveSlot(slotCode: String): Pair<ParkingZone, Int> {
        val ref = ParkingSlot.parse(slotCode) ?: throw NoSuchElementException("자리를 찾을 수 없습니다")
        val zone = zonePort.findById(ref.zoneId) ?: throw NoSuchElementException("자리를 찾을 수 없습니다")
        if (ref.slotNo < zone.startNo || ref.slotNo > zone.startNo + zone.slotCount - 1) {
            throw NoSuchElementException("자리를 찾을 수 없습니다")
        }
        return zone to ref.slotNo
    }

    /** token(허브 경유) 있으면 참석자 확인 후 미입력 필드만 보강 — 이미 입력된 값은 덮지 않는다. */
    private fun prefillFromToken(command: SelfParkCommand): SelfParkCommand {
        if (command.token.isNullOrBlank()) return command
        val guest = guestPort.findByToken(command.token) ?: return command
        return command.copy(
            vipName = command.vipName?.takeIf { it.isNotBlank() } ?: guest.name,
            phone = command.phone?.takeIf { it.isNotBlank() } ?: guest.phone,
        )
    }
}
