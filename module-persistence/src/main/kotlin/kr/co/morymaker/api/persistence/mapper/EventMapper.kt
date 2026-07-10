package kr.co.morymaker.api.persistence.mapper

import kr.co.morymaker.api.domain.event.Event
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * event 테이블 MyBatis 매퍼 인터페이스.
 *
 * 헥사고날 레이어: persistence(mapper) — DB 관심사만 담당. 컬럼명 기반 명시 매핑
 * (positional index 금지 — auth `AccountMapper`와 동일 원칙).
 *
 * XML 정의: resources/mapper/event/EventMapper.xml
 *
 * update/updateBranding 2종은 물리적으로 다른 컬럼셋만 SET한다(ADR-001 저장 게이트 분리) —
 * update는 컬러4종·default_idle_mode 컬럼 자체가 SQL에 존재하지 않는다.
 */
@Mapper
interface EventMapper {

    fun selectById(@Param("id") id: String): Event?

    /**
     * @param eventIds null이면 전체 조회, 값이 있으면 IN 필터(빈 리스트면 결과도 빈 리스트).
     */
    fun search(@Param("eventIds") eventIds: List<String>?): List<Event>

    fun insert(event: Event)

    /** 일반 필드 갱신(§2-4) — name/event_date/place/type/kv/status/active만. */
    fun update(event: Event)

    /** 브랜딩 갱신(§11-1) — bg/point/title/body_color·kv·default_idle_mode만. */
    fun updateBranding(event: Event)
}
