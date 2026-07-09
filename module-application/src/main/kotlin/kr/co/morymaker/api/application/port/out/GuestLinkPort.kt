package kr.co.morymaker.api.application.port.out

/**
 * `guest` 테이블 교차 최소 SPI — parking→guest 방향(3-7 매핑, D5 완성). 기존 `ParkingLinkPort`
 * (guest→parking 방향, 동결)의 대칭 신설이다 — 두 포트는 방향이 반대라 서로 침범하지 않는다.
 *
 * `GuestPort`(참석자 CRUD 전체)를 parking 서비스에 그대로 노출하지 않는 이유는 최소 SPI 원칙
 * 유지 — parking이 실제로 필요한 동작 2개만 이 포트에 담는다.
 */
interface GuestLinkPort {

    /** 주차 매핑(3-7) — plate 완전일치 우선, 없으면 phone 보조로 참석자 조회. 취소자 제외. 없으면 null. */
    fun findGuestByPlateOrPhone(eventId: String, plate: String, phone: String?): GuestLink?

    /** 매핑 성공 시 참석자 방문 전이(대기→방문만, 가드) + plate 백필(비어 있을 때만). */
    fun markVisitedAndBackfillPlate(guestId: String, plate: String)
}

/** [GuestLinkPort] 조회 결과 최소 축약(id·name·status). */
data class GuestLink(val guestId: String, val guestName: String, val guestStatus: String)
