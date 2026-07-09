package kr.co.morymaker.api.application.port.out

import kr.co.morymaker.api.domain.guest.Guest

/**
 * 참석자 영속 포트-out — module-persistence의 `GuestPersistenceAdapter`가 구현한다.
 *
 * ACL 원칙(mybatis-advanced.md §4): 이 인터페이스는 도메인 언어(eventId·gid)로 명명하고,
 * Adapter가 매퍼(DB 언어)로 번역한다.
 */
interface GuestPort {

    /** 쓰기 대상 단건 조회(pure 도메인, seatLabel 미포함). 없으면 null. */
    fun fetchById(eventId: String, gid: String): Guest?

    /** 상세 조회(seatLabel 조인). 없으면 null. */
    fun fetchDetailById(eventId: String, gid: String): GuestListItem?

    /** 체크인 by token(seatLabel 조인). 없으면 null. */
    fun fetchDetailByToken(eventId: String, token: String): GuestListItem?

    /**
     * 전역 token 조회(공개 경로 전용, seatLabel 조인) — eventId 파라미터가 없다. token은 전역
     * UNIQUE(`uk_guest_token`)라 무인증 상태에서도 event 경계를 안전하게 넘어 단건을 특정할 수
     * 있다(요청자가 event를 지정할 여지 자체가 없음 — token 소지가 곧 단일 guest 자연 스코프).
     * 없으면 null.
     */
    fun findByToken(token: String): GuestListItem?

    /** 목록/검색(seatLabel 조인 + 페이징). */
    fun search(eventId: String, query: GuestSearchQuery): List<GuestListItem>

    /** 검색 결과 총 건수(meta.total / searchState 계산용 — paging=false 호출 권장). */
    fun countSearch(eventId: String, query: GuestSearchQuery): Int

    /** 토큰 UNIQUE 충돌 재시도(D6)용 존재 확인. */
    fun existsByToken(token: String): Boolean

    fun insert(guest: Guest)

    /** 전 필드 update(CRUD·상태전이 공용). */
    fun update(guest: Guest)
}
