package kr.co.morymaker.api.persistence.mapper

import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.application.port.out.GuestSearchQuery
import kr.co.morymaker.api.domain.guest.Guest
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * guest 테이블 MyBatis 매퍼 인터페이스.
 *
 * 헥사고날 레이어: persistence(mapper) — DB 관심사만. 컬럼명 기반 명시 매핑(positional index
 * 금지 — EventMapper와 동일 원칙).
 *
 * XML 정의: resources/mapper/guest/GuestMapper.xml
 */
@Mapper
interface GuestMapper {

    /** 쓰기 대상(pure 도메인, seat_group JOIN 없음). */
    fun selectById(@Param("eventId") eventId: String, @Param("gid") gid: String): Guest?

    /** 상세(seat_group LEFT JOIN — seatLabel). */
    fun selectDetailById(@Param("eventId") eventId: String, @Param("gid") gid: String): GuestListItem?

    /** 체크인 by token(seat_group LEFT JOIN). */
    fun selectDetailByToken(@Param("eventId") eventId: String, @Param("token") token: String): GuestListItem?

    /** 목록/검색(seat_group LEFT JOIN + 페이징). `query.paging=false`면 전체 반환. */
    fun search(@Param("eventId") eventId: String, @Param("query") query: GuestSearchQuery): List<GuestListItem>

    /** 검색 총 건수(paging 무관 — WHERE 조건만 적용). */
    fun countSearch(@Param("eventId") eventId: String, @Param("query") query: GuestSearchQuery): Int

    fun existsByToken(@Param("token") token: String): Boolean

    fun insert(guest: Guest)

    fun update(guest: Guest)
}
