package kr.co.morymaker.api.application.port.out

import kr.co.morymaker.api.domain.guest.Guest
import java.time.Instant

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

    /**
     * 조건부 체크인 전이(guestId 대상) — `status != '참석'`일 때만 참석으로 전이하고
     * `visitAt`을 기록한다. 이미 참석 상태면 영향 0행(가드). 반환값(affected rows)으로 "이 호출이
     * 실제 전이를 확정했는지"를 판정한다.
     *
     * ⚠️ 호출 순서 계약 — **이 대상 행을 먼저 SELECT하지 않은 트랜잭션에서 호출해야 한다.**
     * MariaDB의 InnoDB 스냅샷 격리(REPEATABLE READ)는 같은 트랜잭션 안에서 이 행을 먼저 읽어
     * 스냅샷을 확정한 뒤 다른 트랜잭션이 그 사이 이 행을 커밋 변경했다면, 이어지는 UPDATE가
     * MySQL의 semi-consistent read처럼 최신값을 다시 평가하지 않고 `ER_CHECKREAD`(1020, "Record
     * has changed since last read")로 즉시 실패한다(`SELECT FOR UPDATE`의 갭락 데드락과는 다른
     * 별개의 함정 — kiosk 동시성 실 DB 검증에서 직접 재현·확인). 그래서 이 메서드는 "먼저 읽어
     * 존재를 확인한 뒤 조건부로 쓴다"가 아니라 "먼저 blind 조건부 UPDATE를 시도하고, 그 뒤에
     * 상태를 다시 조회해 결과를 해석한다" 순서로 호출해야 한다([CheckinSupport] 참조).
     */
    fun markAttendedIfNotAttended(eventId: String, gid: String, visitAt: Instant): Int

    /**
     * 조건부 체크인 전이(token 대상) — [markAttendedIfNotAttended]와 동일 가드·동일 호출
     * 순서 계약(먼저 읽지 않고 blind UPDATE 먼저)이며, SCN 스캔 체크인(token 기반) 경로가
     * guestId를 사전에 알지 못하는 경우를 위한 변형이다.
     */
    fun markAttendedIfNotAttendedByToken(eventId: String, token: String, visitAt: Instant): Int
}
