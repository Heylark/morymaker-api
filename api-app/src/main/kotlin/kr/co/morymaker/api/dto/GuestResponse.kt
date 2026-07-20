package kr.co.morymaker.api.dto

import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.domain.guest.Guest
import java.time.Instant

/**
 * 참석자 응답 DTO(camelCase) — 목록·단건 조회·등록·수정 응답 공통 사용(EventResponse 패턴).
 *
 * `seatLabel`은 [GuestListItem]에서 온 경우만 채워진다 — [Guest] 단독(등록 직후 등)은 항상 null
 * (02-architect §6-4).
 */
data class GuestResponse(
    val id: String,
    val name: String,
    val org: String?,
    val title: String?,
    val phone: String?,
    val plate: String?,
    val seatGroupId: String?,
    val seatLabel: String?,
    val status: String,
    val src: String,
    val visitAt: Instant?,
    val token: String,
    val createdAt: Instant,
)

fun Guest.toResponse(): GuestResponse = GuestResponse(
    id = id,
    name = name,
    org = org,
    title = title,
    phone = phone,
    plate = plate,
    seatGroupId = seatGroupId,
    seatLabel = null,
    status = status,
    src = src,
    visitAt = visitAt,
    token = token,
    createdAt = createdAt,
)

fun GuestListItem.toResponse(): GuestResponse = GuestResponse(
    id = id,
    name = name,
    org = org,
    title = title,
    phone = phone,
    plate = plate,
    seatGroupId = seatGroupId,
    seatLabel = seatLabel,
    status = status,
    src = src,
    visitAt = visitAt,
    token = token,
    createdAt = createdAt,
)
