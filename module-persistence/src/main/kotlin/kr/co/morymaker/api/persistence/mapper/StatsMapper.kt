package kr.co.morymaker.api.persistence.mapper

import kr.co.morymaker.api.application.port.out.ArrivalItem
import kr.co.morymaker.api.application.port.out.SrcRegistrationCount
import kr.co.morymaker.api.application.port.out.TimelineBucket
import kr.co.morymaker.api.application.port.out.ZoneOccupancy
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * 통계 집계 전용 MyBatis 매퍼 인터페이스(§8, 신설) — guest·parking_zone·parking_record를
 * **읽기만** 한다. 기존 GuestMapper·ParkingZoneMapper·ParkingRecordMapper는 0줄 변경(동결
 * 경계 — 02-architect §2).
 *
 * 헥사고날 레이어: persistence(mapper) — DB 관심사만. 컬럼명 기반 명시 매핑(positional index
 * 금지 — GuestMapper·ParkingZoneMapper와 동일 원칙).
 *
 * XML 정의: resources/mapper/stats/StatsMapper.xml
 */
@Mapper
interface StatsMapper {

    /** src별 등록·실참석(취소 제외) — GROUP BY src. */
    fun countRegistrationBySrc(@Param("eventId") eventId: String): List<SrcRegistrationCount>

    /** 구획별 점유(활성 주차중 기준) — GROUP BY zone_id, 모든 구획 행 반환. */
    fun aggregateByZone(@Param("eventId") eventId: String): List<ZoneOccupancy>

    /** 시간버킷별 체크인(status=참석) 건수 — GROUP BY 시간버킷, bucketKey 오름차순. */
    fun aggregateTimeline(@Param("eventId") eventId: String): List<TimelineBucket>

    /** 도착순(visit_at 존재·취소 제외) — visit_at 내림차순. */
    fun selectArrivals(@Param("eventId") eventId: String): List<ArrivalItem>
}
