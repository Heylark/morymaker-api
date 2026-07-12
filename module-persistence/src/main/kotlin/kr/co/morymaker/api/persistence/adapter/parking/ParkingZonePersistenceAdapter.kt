package kr.co.morymaker.api.persistence.adapter.parking

import kr.co.morymaker.api.application.port.out.ParkingSlotTitlePort
import kr.co.morymaker.api.application.port.out.ParkingZonePort
import kr.co.morymaker.api.domain.parking.ParkingSlotTitle
import kr.co.morymaker.api.domain.parking.ParkingZone
import kr.co.morymaker.api.persistence.mapper.ParkingSlotTitleMapper
import kr.co.morymaker.api.persistence.mapper.ParkingZoneMapper
import org.springframework.stereotype.Component

/**
 * [ParkingZonePort]·[ParkingSlotTitlePort] 구현체 — 두 테이블이 구획 애그리게잇(구획 본체 +
 * 자리 타이틀 override)을 이루므로 하나의 어댑터가 함께 담당한다(02-architect §1).
 *
 * 헥사고날 레이어: Persistence(adapter). `internal`: application 계층은 두 포트 인터페이스만
 * 의존한다.
 */
@Component
internal class ParkingZonePersistenceAdapter(
    private val zoneMapper: ParkingZoneMapper,
    private val slotTitleMapper: ParkingSlotTitleMapper,
) : ParkingZonePort, ParkingSlotTitlePort {

    override fun findByEvent(eventId: String): List<ParkingZone> = zoneMapper.findByEvent(eventId)

    override fun fetchById(eventId: String, id: String): ParkingZone? = zoneMapper.fetchById(eventId, id)

    override fun findById(zoneId: String): ParkingZone? = zoneMapper.findById(zoneId)

    override fun insert(zone: ParkingZone) = zoneMapper.insert(zone)

    override fun update(zone: ParkingZone) = zoneMapper.update(zone)

    override fun findByZoneId(zoneId: String): List<ParkingSlotTitle> = slotTitleMapper.findByZoneId(zoneId)

    override fun findByEventId(eventId: String): List<ParkingSlotTitle> = slotTitleMapper.findByEventId(eventId)

    override fun deleteByZoneId(eventId: String, zoneId: String) = slotTitleMapper.deleteByZoneId(eventId, zoneId)

    override fun insertBatch(titles: List<ParkingSlotTitle>) {
        // foreach 배치 INSERT는 빈 리스트면 문법 오류(VALUES 절 없음) — 호출 전 가드.
        if (titles.isNotEmpty()) slotTitleMapper.insertBatch(titles)
    }
}
