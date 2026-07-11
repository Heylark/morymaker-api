package kr.co.morymaker.api.dto

import kr.co.morymaker.api.application.port.`in`.LookupItem

/**
 * 공개 kiosk 이름검색 응답(KIO-02) — 구조적 미노출: phone·plate·title·token은 이 shape에
 * 애초에 필드가 없다(PublicSlotResponse 선례 — 마스킹 로직 불요). `guestId`는 후속 체크인(KIO-04)
 * capability(랜덤 UUID·비열거·PII 아님)로만 쓰인다.
 */
data class PublicAttendeeResponse(
    val guestId: String,
    val name: String,
    val org: String?,
    val seatLabel: String?,
    val status: String,
)

fun LookupItem.toPublicAttendeeResponse(): PublicAttendeeResponse = PublicAttendeeResponse(
    guestId = guest.id,
    name = guest.name,
    org = guest.org,
    seatLabel = guest.seatLabel,
    status = guest.status,
)
