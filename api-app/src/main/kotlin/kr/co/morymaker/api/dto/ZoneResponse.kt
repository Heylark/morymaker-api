package kr.co.morymaker.api.dto

import kr.co.morymaker.api.application.port.`in`.ZoneView

/** 주차 구획 응답 DTO(camelCase, §6-1~6-3 공통 — GuestResponse 패턴). */
data class ZoneResponse(
    val id: String,
    val part1: String?,
    val part2: String?,
    val part3: String?,
    val part4: String?,
    val zoneName: String,
    val outdoor: Boolean,
    val startNo: Int,
    val slotCount: Int,
    val titleOverrides: Map<String, String>,
)

fun ZoneView.toResponse(): ZoneResponse = ZoneResponse(
    id = id,
    part1 = part1,
    part2 = part2,
    part3 = part3,
    part4 = part4,
    zoneName = zoneName,
    outdoor = outdoor,
    startNo = startNo,
    slotCount = slotCount,
    titleOverrides = titleOverrides,
)
