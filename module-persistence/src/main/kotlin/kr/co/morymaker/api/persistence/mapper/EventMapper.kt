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
 * 이번 REQ 범위(foundation)는 목록·단건 조회·생성 3개 엔드포인트만 — update/delete는 후속 도메인
 * REQ(행사 정보 수정 ADM-02, 브랜딩 ADM-04)에서 추가한다.
 */
@Mapper
interface EventMapper {

    fun selectById(@Param("id") id: String): Event?

    /**
     * @param eventIds null이면 전체 조회, 값이 있으면 IN 필터(빈 리스트면 결과도 빈 리스트).
     */
    fun search(@Param("eventIds") eventIds: List<String>?): List<Event>

    fun insert(event: Event)
}
