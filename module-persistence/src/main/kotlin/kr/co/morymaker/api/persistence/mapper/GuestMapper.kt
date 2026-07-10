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

    /** 전역 token 조회(공개 경로 전용, event_id 조건 없음 — seat_group LEFT JOIN). */
    fun selectDetailByGlobalToken(@Param("token") token: String): GuestListItem?

    /** 목록/검색(seat_group LEFT JOIN + 페이징). `query.paging=false`면 전체 반환. */
    fun search(@Param("eventId") eventId: String, @Param("query") query: GuestSearchQuery): List<GuestListItem>

    /** 검색 총 건수(paging 무관 — WHERE 조건만 적용). */
    fun countSearch(@Param("eventId") eventId: String, @Param("query") query: GuestSearchQuery): Int

    fun existsByToken(@Param("token") token: String): Boolean

    fun insert(guest: Guest)

    fun update(guest: Guest)

    // ➕ §6 신규 — parking→guest 매핑(3-7, P3) 조회·전이. GuestLinkAdapter가 위임(테이블 소유권 유지).

    /** plate 완전일치(공백 정규화) 활성 참석자 조회 — 취소자 제외. 없으면 null. */
    fun selectActiveByPlate(@Param("eventId") eventId: String, @Param("plate") plate: String): Guest?

    /** phone 완전일치 활성 참석자 조회(plate 매칭 실패 시 보조) — 취소자 제외. 없으면 null. */
    fun selectActiveByPhone(@Param("eventId") eventId: String, @Param("phone") phone: String): Guest?

    /** 대기→방문 가드 전이(매핑 성공 시). 대기 상태가 아니면 영향 0행(가드). */
    fun markVisitedIfWaiting(@Param("gid") gid: String)

    /** plate 백필 — 기존 값이 비어 있을 때만 갱신(가드). */
    fun backfillPlateIfEmpty(@Param("gid") gid: String, @Param("plate") plate: String)

    // ➕ §12 신규 — seat→guest 매핑(M1 payload 검증·M3 동기화). GuestSeatLinkAdapter가 위임(테이블 소유권 유지).

    /** event 소속 guestId만 필터링(§12-5 M1 payload 검증). */
    fun selectExistingIds(@Param("eventId") eventId: String, @Param("guestIds") guestIds: List<String>): List<String>

    /** guest.seat_group_id 일괄 갱신(§12-5 M3 동기화). seatGroupId=null이면 해제. */
    fun updateSeatGroupId(@Param("guestIds") guestIds: List<String>, @Param("seatGroupId") seatGroupId: String?)
}
