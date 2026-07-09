package kr.co.morymaker.api.persistence.mapper

import kr.co.morymaker.api.domain.parking.ParkingSlotTitle
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * parking_slot_title 테이블 MyBatis 매퍼 인터페이스(§6-3, 신설) — 자리 타이틀 override.
 *
 * 복합 자연키(zone_id, slot_no)의 매핑성 테이블이라 하드 삭제(delete-insert)를 쓴다(mybatis.md
 * 표준 6개 메서드의 소프트 삭제 예외).
 *
 * XML 정의: resources/mapper/parking/ParkingSlotTitleMapper.xml
 */
@Mapper
interface ParkingSlotTitleMapper {

    /** 구획 1건의 override 전체(§6-3 슬롯 파생용). */
    fun findByZoneId(@Param("zoneId") zoneId: String): List<ParkingSlotTitle>

    /** 행사 전체 override — 목록(§6-1) N+1 회피 일괄 조회(parking_zone JOIN으로 event 격리). */
    fun findByEventId(@Param("eventId") eventId: String): List<ParkingSlotTitle>

    /** zone_id 기준 전삭제(§6-3 delete-insert 1단계). */
    fun deleteByZoneId(@Param("zoneId") zoneId: String)

    /** 재삽입(§6-3 delete-insert 2단계, foreach 배치). */
    fun insertBatch(@Param("list") titles: List<ParkingSlotTitle>)
}
