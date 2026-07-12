package kr.co.morymaker.api.application.port.out

/**
 * `parking_record` 교차 최소 SPI — guest 방향 최소 read(plate→활성record / guestId→slot) +
 * guest_id 백필 write만. 전체 parking 도메인(주차 등록·출차·zone 등)은 이 포트에 혼입하지 않는다
 * (다른 애그리게잇 — GuestPort와 별개 SPI로 유지, 후속 주차 REQ가 별도 확장).
 */
interface ParkingLinkPort {

    /** 역방향 지연매칭(§4-10) — event 내 활성('주차중') 주차기록 id. 없으면 null. */
    fun findActiveRecordIdByPlate(eventId: String, plate: String): String?

    /** 체크인 응답 주차 병기(§5-1) — guest에 연결된 활성 slot. 없으면 null. */
    fun findActiveSlotByGuestId(eventId: String, guestId: String): ParkingSlotRef?

    /** `parking_record.guest_id` 백필. eventId는 cross-event 격리 방어심층 스코핑용. */
    fun linkGuest(eventId: String, recordId: String, guestId: String)
}

/** 체크인 응답의 주차 위치 display 최소 파생용. */
data class ParkingSlotRef(val slotSig: String)
