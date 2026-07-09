package kr.co.morymaker.api.dto

import kr.co.morymaker.api.application.port.`in`.LookupItem

/** 실행자 통합조회 응답(§9-1) — 명단 정보에 좌석·주차 위치를 서버가 병기해 안내데스크 한 화면에 표시한다. */
data class LookupResponse(
    val guestId: String,
    val name: String,
    val org: String?,
    val title: String?,
    val status: String,
    val seatLabel: String?,
    val phone: String?,
    val plate: String?,
    val parking: LookupParkingView?,
)

data class LookupParkingView(val slotSig: String, val display: String)

fun LookupItem.toResponse(): LookupResponse = LookupResponse(
    guestId = guest.id,
    name = guest.name,
    org = guest.org,
    title = guest.title,
    status = guest.status,
    seatLabel = guest.seatLabel,
    phone = guest.phone,
    plate = guest.plate,
    parking = parking?.let { LookupParkingView(it.slotSig, it.display) },
)
