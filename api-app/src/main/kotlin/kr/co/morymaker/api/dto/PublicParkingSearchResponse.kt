package kr.co.morymaker.api.dto

import kr.co.morymaker.api.domain.parking.ParkingRecord
import kr.co.morymaker.api.domain.parking.ParkingSlot

/**
 * 공개 kiosk 주차검색 응답(KIO-05) — 구조적 미노출: `phone`·`vipName`·`guestId`·
 * `registeredBy`·`id`·`slotSig`·`registeredAt`·`status`·`reviewNeeded`는 이 shape에 애초에
 * 필드가 없다. `plate`는 다건 매칭 시 본인 차량 선택 UX를 위해 full 노출한다(잔여 위험은 rate
 * limit으로 완화, 모리메이커 확인 대상 — 다건 노출 없이 검색 자체가 불가능하므로 불가피).
 */
data class PublicParkingSearchResponse(val plate: String, val slotDisplay: String)

fun ParkingRecord.toPublicParkingSearchResponse(): PublicParkingSearchResponse = PublicParkingSearchResponse(
    plate = plate,
    slotDisplay = ParkingSlot.slotDisplay(slotSig),
)
