package kr.co.morymaker.api.persistence.mapper

import kr.co.morymaker.api.application.port.out.RecordSearchQuery
import kr.co.morymaker.api.domain.parking.ParkingRecord
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * parking_record 테이블 매퍼 — guest 방향 최소 3쿼리(교차 최소 범위, 동결) + §6 주차 도메인
 * CRUD(zone·record 수직 신설, 이번 REQ가 이어 붙임)를 함께 소유한다.
 *
 * 테이블 소유권 원칙(mybatis.md "보조 메서드 위치"): parking_record DML은 이 매퍼가 소유한다.
 *
 * DB 언어 반환(String — slot_sig 원문)만 노출하고, 도메인/포트 타입([kr.co.morymaker.api.application.port.out.ParkingSlotRef])으로의
 * 번역은 `ParkingLinkAdapter`(ACL)가 담당한다(mybatis-advanced.md §4).
 */
@Mapper
interface ParkingRecordMapper {

    // ❄️ 동결 — 시그니처·SQL 불변(guest→parking 교차 최소 SPI, GuestService·CheckinService 의존).

    /** 역방향 지연매칭(§4-10) — 활성('주차중') 주차기록 id. 없으면 null. */
    fun selectActiveRecordIdByPlate(@Param("eventId") eventId: String, @Param("plate") plate: String): String?

    /** 체크인 응답 병기(§5-1) — guest에 연결된 활성 slot_sig. 없으면 null. */
    fun selectActiveSlotSigByGuestId(@Param("eventId") eventId: String, @Param("guestId") guestId: String): String?

    /** guest_id 백필 — §6 매핑(3-7)도 이 메서드를 그대로 재사용한다(의미 동일: guest_id UPDATE). */
    fun linkGuest(@Param("recordId") recordId: String, @Param("guestId") guestId: String)

    // ➕ §6 신규 — parking_record 전체 CRUD(zone·record 수직 신설).

    /** 대상 자리 활성 기록 조회(P1, v2) — 케이스 판정용 단순 조회. 동시성 방어는 active_key UNIQUE 단독. */
    fun selectActiveBySlot(@Param("eventId") eventId: String, @Param("slotSig") slotSig: String): ParkingRecord?

    /** 같은 차량번호의 다른-자리 활성 기록 조회(§4-1 케이스 판정용). */
    fun selectActiveByPlate(@Param("eventId") eventId: String, @Param("plate") plate: String): ParkingRecord?

    /** 단건 조회(§6-7·§6-8). 없으면 null. */
    fun selectById(@Param("eventId") eventId: String, @Param("id") id: String): ParkingRecord?

    /** 목록(§6-5). zoneId·status·reviewNeeded·plateTail(뒷자리 검색) 필터. */
    fun search(@Param("eventId") eventId: String, @Param("query") query: RecordSearchQuery): List<ParkingRecord>

    /** 신규 삽입(§4-1 케이스 B·E) — active_key UNIQUE 위반 시 DuplicateKeyException. */
    fun insert(record: ParkingRecord)

    /** 자리 이동(§4-1 케이스 C·D) — slot_sig·zone_id·review_needed·guest_id 갱신. */
    fun updateSlotMove(record: ParkingRecord)

    /** 본인 재등록(§4-1 케이스 A) — registered_at만 현재 시각으로 갱신. */
    fun touchRegisteredAt(@Param("id") id: String)

    /** 출차(§6-7) — status→출차(active_key NULL화). */
    fun checkout(@Param("id") id: String)

    /** 승계 확인 배지 해제(§6-8) — review_needed→0. */
    fun clearReview(@Param("id") id: String)
}
