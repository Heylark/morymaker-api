package kr.co.morymaker.api.persistence.adapter.parking

import kr.co.morymaker.api.application.port.out.ParkingRecordPort
import kr.co.morymaker.api.application.port.out.RecordSearchQuery
import kr.co.morymaker.api.domain.parking.ParkingRecord
import kr.co.morymaker.api.persistence.mapper.ParkingRecordMapper
import org.springframework.stereotype.Component

/**
 * [ParkingRecordPort] 구현체 — MyBatis 매퍼 위임. `ParkingLinkAdapter`(guest 방향 최소 SPI,
 * 동결)와 별개 어댑터다 — 이 클래스는 parking_record 전체 CRUD(zone·record 수직 신설)를 맡는다.
 *
 * 헥사고날 레이어: Persistence(adapter). `internal`: application 계층은 [ParkingRecordPort]
 * 인터페이스만 의존한다.
 */
@Component
internal class ParkingRecordPersistenceAdapter(
    private val recordMapper: ParkingRecordMapper,
) : ParkingRecordPort {

    override fun selectActiveBySlot(eventId: String, slotSig: String): ParkingRecord? =
        recordMapper.selectActiveBySlot(eventId, slotSig)

    override fun selectActiveByPlate(eventId: String, plate: String): ParkingRecord? =
        recordMapper.selectActiveByPlate(eventId, plate)

    override fun fetchById(eventId: String, id: String): ParkingRecord? = recordMapper.selectById(eventId, id)

    override fun search(eventId: String, query: RecordSearchQuery): List<ParkingRecord> =
        recordMapper.search(eventId, query)

    override fun insert(record: ParkingRecord) = recordMapper.insert(record)

    override fun updateSlotMove(record: ParkingRecord) = recordMapper.updateSlotMove(record)

    override fun touchRegisteredAt(eventId: String, id: String) = recordMapper.touchRegisteredAt(eventId, id)

    override fun checkout(eventId: String, id: String) = recordMapper.checkout(eventId, id)

    override fun clearReview(eventId: String, id: String) = recordMapper.clearReview(eventId, id)

    // 기존 동결 scalar linkGuest를 그대로 재사용한다(02-architect §6 — 의미 동일: guest_id UPDATE).
    override fun linkGuest(eventId: String, recordId: String, guestId: String) =
        recordMapper.linkGuest(eventId, recordId, guestId)
}
