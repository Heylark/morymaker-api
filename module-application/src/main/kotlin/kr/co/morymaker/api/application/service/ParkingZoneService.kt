package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.ParkingZoneUseCase
import kr.co.morymaker.api.application.port.`in`.SlotView
import kr.co.morymaker.api.application.port.`in`.ZoneCreateCommand
import kr.co.morymaker.api.application.port.`in`.ZoneSlotsView
import kr.co.morymaker.api.application.port.`in`.ZoneUpdateCommand
import kr.co.morymaker.api.application.port.`in`.ZoneView
import kr.co.morymaker.api.application.port.out.ParkingSlotTitlePort
import kr.co.morymaker.api.application.port.out.ParkingZonePort
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.parking.ParkingSlot
import kr.co.morymaker.api.domain.parking.ParkingSlotTitle
import kr.co.morymaker.api.domain.parking.ParkingZone
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * [ParkingZoneUseCase] 구현체 — 구획 CRUD(§6-1~6-3)·QR 발급용 자리 파생(§6-4)을 담당한다.
 *
 * 헥사고날 레이어: application(service). `internal`: api-app은 [ParkingZoneUseCase] 인터페이스만
 * 의존한다.
 */
@Service
internal class ParkingZoneService(
    private val zonePort: ParkingZonePort,
    private val slotTitlePort: ParkingSlotTitlePort,
    private val eventScopeGuard: EventScopeGuard,
) : ParkingZoneUseCase {

    @Transactional(readOnly = true)
    override fun listZones(eventId: String): List<ZoneView> {
        eventScopeGuard.assertAccess(eventId)
        val zones = zonePort.findByEvent(eventId)
        val titlesByZone = slotTitlePort.findByEventId(eventId).groupBy { it.zoneId }
        return zones.map { it.toView(titlesByZone[it.id].orEmpty()) }
    }

    @Transactional
    override fun createZone(eventId: String, command: ZoneCreateCommand): ZoneView {
        eventScopeGuard.assertAccess(eventId)
        val zone = ParkingZone(
            id = UUID.randomUUID().toString(),
            eventId = eventId,
            part1 = command.part1,
            part2 = command.part2,
            part3 = command.part3,
            part4 = command.part4,
            startNo = command.startNo,
            slotCount = command.slotCount,
            createdAt = Instant.now(),
        )
        zonePort.insert(zone)
        // 신규 구획은 override가 있을 수 없다(재생성 시 번호로 초기화 — §6-2).
        return zone.toView(emptyList())
    }

    @Transactional
    override fun updateZone(eventId: String, zid: String, command: ZoneUpdateCommand): ZoneView {
        eventScopeGuard.assertAccess(eventId)
        val existing = zonePort.fetchById(eventId, zid) ?: throw NoSuchElementException("주차 구획을 찾을 수 없습니다")
        val merged = existing.with(
            part1 = command.part1,
            part2 = command.part2,
            part3 = command.part3,
            part4 = command.part4,
            startNo = command.startNo,
            slotCount = command.slotCount,
        )
        zonePort.update(merged)

        // titleOverrides가 null이면(폼이 함께 전달하지 않은 경우) 타이틀은 건드리지 않는다.
        // null이 아니면(빈 맵 포함) zone_id 기준 전삭제 후 재삽입 — §6-3 delete-insert.
        val overrides = if (command.titleOverrides != null) {
            slotTitlePort.deleteByZoneId(zid)
            val rows = command.titleOverrides.mapNotNull { (slotNoText, title) ->
                val slotNo = slotNoText.toIntOrNull() ?: return@mapNotNull null
                if (title.isBlank()) null else ParkingSlotTitle(zid, slotNo, title)
            }
            if (rows.isNotEmpty()) slotTitlePort.insertBatch(rows)
            rows
        } else {
            slotTitlePort.findByZoneId(zid)
        }
        return merged.toView(overrides)
    }

    @Transactional(readOnly = true)
    override fun getSlotsForQr(eventId: String, zid: String): ZoneSlotsView {
        eventScopeGuard.assertAccess(eventId)
        val zone = zonePort.fetchById(eventId, zid) ?: throw NoSuchElementException("주차 구획을 찾을 수 없습니다")
        val titleBySlotNo = slotTitlePort.findByZoneId(zid).associate { it.slotNo to it.titleOverride }
        val zoneName = ParkingSlot.zoneName(zone.part1, zone.part2, zone.part3, zone.part4)
        val slots = (0 until zone.slotCount).map { index ->
            val slotNo = ParkingSlot.slotNo(zone.startNo, index)
            val slotTitle = ParkingSlot.slotTitle(titleBySlotNo[slotNo], slotNo)
            SlotView(
                slotNo = slotNo,
                slotCode = ParkingSlot.slotCode(zid, slotNo),
                slotFullName = ParkingSlot.slotFullName(zoneName, slotTitle),
            )
        }
        return ZoneSlotsView(zoneName = zoneName, slots = slots)
    }

    private fun ParkingZone.toView(overrides: List<ParkingSlotTitle>): ZoneView = ZoneView(
        id = id,
        part1 = part1,
        part2 = part2,
        part3 = part3,
        part4 = part4,
        zoneName = ParkingSlot.zoneName(part1, part2, part3, part4),
        outdoor = ParkingSlot.outdoor(part1),
        startNo = startNo,
        slotCount = slotCount,
        titleOverrides = overrides.associate { it.slotNo.toString() to it.titleOverride },
    )
}
