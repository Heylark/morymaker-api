package kr.co.morymaker.api.dto

import kr.co.morymaker.api.application.port.`in`.CheckinResult

/**
 * 공개 kiosk 체크인 응답(KIO-04, D-A) — 구조적 미노출: `guest.id`·`visitAt`·`phone`·`plate`·
 * `parking.slotSig`는 이 shape에 애초에 필드가 없다. `parking.display`만 좌석안내(seat-guide)
 * 화면에 병기한다.
 */
data class PublicKioskCheckinResponse(
    val resultCode: String,
    val guest: PublicKioskGuestView,
    val parking: PublicKioskParkingView?,
)

data class PublicKioskGuestView(
    val name: String,
    val org: String?,
    val status: String,
    val seatLabel: String?,
)

data class PublicKioskParkingView(val display: String)

fun CheckinResult.toPublicKioskCheckinResponse(): PublicKioskCheckinResponse = PublicKioskCheckinResponse(
    resultCode = resultCode,
    guest = PublicKioskGuestView(name = guest.name, org = guest.org, status = guest.status, seatLabel = guest.seatLabel),
    parking = parking?.let { PublicKioskParkingView(display = it.display) },
)
