package kr.co.morymaker.api.persistence.adapter.parking

import kr.co.morymaker.api.application.port.out.ParkingLinkPort
import kr.co.morymaker.api.application.port.out.ParkingSlotRef
import kr.co.morymaker.api.persistence.mapper.ParkingRecordMapper
import org.springframework.stereotype.Component

/**
 * [ParkingLinkPort] 구현체 — MyBatis 매퍼 위임 + DB 언어(slot_sig 원문 String) → 포트 타입
 * ([ParkingSlotRef]) 번역(ACL, mybatis-advanced.md §4).
 *
 * 헥사고날 레이어: Persistence(adapter). `internal`: application 계층은 [ParkingLinkPort]
 * 인터페이스만 의존한다.
 */
@Component
internal class ParkingLinkAdapter(
    private val parkingRecordMapper: ParkingRecordMapper,
) : ParkingLinkPort {

    override fun findActiveRecordIdByPlate(eventId: String, plate: String): String? =
        parkingRecordMapper.selectActiveRecordIdByPlate(eventId, plate)

    override fun findActiveSlotByGuestId(eventId: String, guestId: String): ParkingSlotRef? =
        parkingRecordMapper.selectActiveSlotSigByGuestId(eventId, guestId)?.let { ParkingSlotRef(it) }

    override fun linkGuest(eventId: String, recordId: String, guestId: String) =
        parkingRecordMapper.linkGuest(eventId, recordId, guestId)
}
