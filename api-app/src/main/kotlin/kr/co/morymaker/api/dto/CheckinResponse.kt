package kr.co.morymaker.api.dto

import kr.co.morymaker.api.application.port.`in`.CheckinResult
import kr.co.morymaker.api.application.port.out.GuestListItem
import java.time.Instant

/** 체크인 응답(§5-1) — 좌석·주차 위치를 서버가 병기해 프론트 추가 조회를 불요하게 한다(KIO-04). */
data class CheckinResponse(
    val resultCode: String,
    val guest: CheckinGuestView,
    val parking: CheckinParkingView?,
)

data class CheckinGuestView(
    val id: String,
    val name: String,
    val org: String?,
    val status: String,
    val visitAt: Instant?,
    val seatLabel: String?,
)

data class CheckinParkingView(val slotSig: String, val display: String)

fun CheckinResult.toResponse(): CheckinResponse = CheckinResponse(
    resultCode = resultCode,
    guest = guest.toCheckinGuestView(),
    parking = parking?.let { CheckinParkingView(it.slotSig, it.display) },
)

private fun GuestListItem.toCheckinGuestView(): CheckinGuestView = CheckinGuestView(
    id = id,
    name = name,
    org = org,
    status = status,
    visitAt = visitAt,
    seatLabel = seatLabel,
)
