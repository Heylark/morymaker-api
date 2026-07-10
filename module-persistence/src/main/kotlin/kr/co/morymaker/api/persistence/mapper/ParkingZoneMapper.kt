package kr.co.morymaker.api.persistence.mapper

import kr.co.morymaker.api.domain.parking.ParkingZone
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * parking_zone 테이블 MyBatis 매퍼 인터페이스(§6 구획, 신설).
 *
 * 헥사고날 레이어: persistence(mapper) — DB 관심사만. 컬럼명 기반 명시 매핑(positional index
 * 금지 — GuestMapper·EventMapper와 동일 원칙).
 *
 * XML 정의: resources/mapper/parking/ParkingZoneMapper.xml
 */
@Mapper
interface ParkingZoneMapper {

    /** 목록(§6-1). */
    fun findByEvent(@Param("eventId") eventId: String): List<ParkingZone>

    /** 소유 검증 겸 단건 조회(§6-3). 없으면 null. */
    fun fetchById(@Param("eventId") eventId: String, @Param("id") id: String): ParkingZone?

    /** zoneId 단독 조회(공개 자리 QR 경로 전용, §10-3). 잠금 없음. 없으면 null. */
    fun findById(@Param("id") id: String): ParkingZone?

    fun insert(zone: ParkingZone)

    fun update(zone: ParkingZone)
}
