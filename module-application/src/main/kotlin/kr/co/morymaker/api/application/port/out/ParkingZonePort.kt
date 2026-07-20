package kr.co.morymaker.api.application.port.out

import kr.co.morymaker.api.domain.parking.ParkingZone

/**
 * 주차 구획 영속 포트-out — module-persistence의 `ParkingZonePersistenceAdapter`가 구현한다.
 *
 * ACL 원칙(mybatis-advanced.md §4): 이 인터페이스는 도메인 언어(eventId·zid)로 명명하고,
 * Adapter가 매퍼(DB 언어)로 번역한다.
 */
interface ParkingZonePort {

    /** 목록(§6-1). */
    fun findByEvent(eventId: String): List<ParkingZone>

    /** 소유 검증 겸 단건 조회(§6-3 zone 소속 확인). 없으면 null. */
    fun fetchById(eventId: String, id: String): ParkingZone?

    /**
     * zoneId 단독 lock-free 조회(공개 자리 QR 경로 전용, §10-3) — eventId 소유 검증 없이 PK만
     * 으로 조회한다. 공개 경로는 slotCode(zoneId만 포함, eventId 미포함)만으로 구획을 찾아
     * eventId를 이 조회 결과에서 역으로 파생해야 한다(잠금 없는 단순 조회, 동시성 영향 없음).
     * 없으면 null.
     */
    fun findById(zoneId: String): ParkingZone?

    fun insert(zone: ParkingZone)

    fun update(zone: ParkingZone)
}
