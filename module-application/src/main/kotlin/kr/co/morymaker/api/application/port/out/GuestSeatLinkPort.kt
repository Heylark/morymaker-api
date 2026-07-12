package kr.co.morymaker.api.application.port.out

/**
 * `guest` 테이블 교차 최소 SPI — seat→guest 방향(§12-5 M1 payload 검증·M3 동기화). 기존
 * `GuestLinkPort`(parking→guest 방향, 동결)의 대칭 신설이다 — `GuestPort`(참석자 CRUD 전체)를
 * 좌석 서비스에 그대로 노출하지 않고, 실제로 필요한 동작 2개만 이 포트에 담는다.
 */
interface GuestSeatLinkPort {

    /** payload guestId 유효성 검증(§12-5 M1) — 해당 event 소속 guestId만 필터링해 반환. */
    fun filterExistingIds(eventId: String, guestIds: List<String>): Set<String>

    /** M3 동기화 — `guest.seat_group_id` 일괄 갱신(seatGroupId=null이면 해제). 빈 리스트는 무동작. eventId는 cross-event 격리 방어심층 스코핑용. */
    fun updateSeatGroupId(eventId: String, guestIds: List<String>, seatGroupId: String?)
}
