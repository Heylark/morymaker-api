package kr.co.morymaker.api.application.port.out

import kr.co.morymaker.api.domain.parking.ParkingSlotTitle

/**
 * 자리 타이틀 override 영속 포트-out — module-persistence의 `ParkingZonePersistenceAdapter`가
 * 구현한다(zone과 생명주기를 함께하는 매핑성 테이블 — 애그리게잇 8 참조).
 *
 * 하드 삭제(delete-insert) 대상이라 소프트 삭제 관례를 따르지 않는다(§6-3).
 */
interface ParkingSlotTitlePort {

    /** 구획 1건의 override 전체(§6-1 titleOverrides 조인, §6-3 슬롯 파생). */
    fun findByZoneId(zoneId: String): List<ParkingSlotTitle>

    /** 행사 전체 override — 목록(§6-1) N+1 회피용 일괄 조회(parking_zone JOIN으로 event 격리). */
    fun findByEventId(eventId: String): List<ParkingSlotTitle>

    /** zone_id 기준 전삭제(§6-3 delete-insert 1단계). eventId는 cross-event 격리 방어심층 스코핑용(parking_zone EXISTS 서브쿼리 경유). */
    fun deleteByZoneId(eventId: String, zoneId: String)

    /** 재삽입(§6-3 delete-insert 2단계). 빈 리스트면 호출하지 않아도 안전(no-op)해야 한다. */
    fun insertBatch(titles: List<ParkingSlotTitle>)
}
