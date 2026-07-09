package kr.co.morymaker.api.persistence.mapper

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * parking_record 테이블 매퍼 — guest 방향 최소 3쿼리만(D5, ADR-PARKING-MAP-BOUNDARY).
 *
 * 테이블 소유권 원칙(mybatis.md "보조 메서드 위치"): parking_record DML은 이 매퍼가 소유한다.
 * 전체 parking 도메인(zone·slot·출차 등)의 CRUD는 §6 REQ가 이 인터페이스에 이어 붙인다 — 지금은
 * guest 쪽에서 필요한 최소 3개만 정의한다.
 *
 * DB 언어 반환(String — slot_sig 원문)만 노출하고, 도메인/포트 타입([kr.co.morymaker.api.application.port.out.ParkingSlotRef])으로의
 * 번역은 `ParkingLinkAdapter`(ACL)가 담당한다(mybatis-advanced.md §4).
 */
@Mapper
interface ParkingRecordMapper {

    /** 역방향 지연매칭(§4-10) — 활성('주차중') 주차기록 id. 없으면 null. */
    fun selectActiveRecordIdByPlate(@Param("eventId") eventId: String, @Param("plate") plate: String): String?

    /** 체크인 응답 병기(§5-1) — guest에 연결된 활성 slot_sig. 없으면 null. */
    fun selectActiveSlotSigByGuestId(@Param("eventId") eventId: String, @Param("guestId") guestId: String): String?

    /** guest_id 백필. */
    fun linkGuest(@Param("recordId") recordId: String, @Param("guestId") guestId: String)
}
