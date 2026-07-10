package kr.co.morymaker.api.persistence.mapper

import kr.co.morymaker.api.domain.seat.SeatGroup
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * seat_group 테이블 MyBatis 매퍼 인터페이스(§12-1~3, 신설).
 *
 * 헥사고날 레이어: persistence(mapper) — DB 관심사만. 컬럼명 기반 명시 매핑(positional index
 * 금지 — GuestMapper·ParkingZoneMapper와 동일 원칙).
 *
 * XML 정의: resources/mapper/seat/SeatGroupMapper.xml
 */
@Mapper
interface SeatGroupMapper {

    /** 목록(§12-1). */
    fun findByEvent(@Param("eventId") eventId: String): List<SeatGroup>

    /** 소유 검증 겸 단건 조회(§12-3). 없으면 null. */
    fun fetchById(@Param("eventId") eventId: String, @Param("id") id: String): SeatGroup?

    /** groupNo 기준 단건 조회(§12-4·§12-5 그룹 해석). 없으면 null. */
    fun fetchByGroupNo(@Param("eventId") eventId: String, @Param("groupNo") groupNo: Int): SeatGroup?

    /** 신규 등록(§12-2) 채번 — 현재 최대값+1. */
    fun nextGroupNo(@Param("eventId") eventId: String): Int

    /** 신규 등록(§12-2) 채번 — 현재 최대값+1. */
    fun nextSortOrder(@Param("eventId") eventId: String): Int

    fun insert(group: SeatGroup)

    fun update(group: SeatGroup)

    fun delete(@Param("eventId") eventId: String, @Param("id") id: String)
}
