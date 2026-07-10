package kr.co.morymaker.api.persistence.mapper

import kr.co.morymaker.api.domain.idle.IdleContent
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * idle_content 테이블 MyBatis 매퍼 인터페이스(§11-2~4, 신설).
 *
 * 헥사고날 레이어: persistence(mapper) — DB 관심사만. 컬럼명 기반 명시 매핑(positional index
 * 금지 — EventMapper·ParkingZoneMapper와 동일 원칙).
 *
 * XML 정의: resources/mapper/idle/IdleContentMapper.xml
 */
@Mapper
interface IdleContentMapper {

    /** 목록(§11-2). sortOrder 순 — 관리자·키오스크 공개 라우트 양쪽에서 사용한다. */
    fun findByEvent(@Param("eventId") eventId: String): List<IdleContent>

    /** 소유 검증 겸 단건 조회(§11-4). 없으면 null. */
    fun fetchById(@Param("eventId") eventId: String, @Param("id") id: String): IdleContent?

    fun insert(content: IdleContent)

    fun update(content: IdleContent)
}
