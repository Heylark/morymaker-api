package kr.co.morymaker.api.dto

import kr.co.morymaker.api.application.port.`in`.SlotView

/** 자리 QR 응답 DTO(§6-4b 신설) — scanUrl은 qrZip과 동일 조립식을 공유(SSOT). web ADM-07 QrPreview 소비 계약. */
data class SlotResponse(
    val slotNo: Int,
    val slotCode: String,
    val slotFullName: String,
    val scanUrl: String,
)

fun SlotView.toResponse(qrBaseUrl: String): SlotResponse = SlotResponse(
    slotNo = slotNo,
    slotCode = slotCode,
    slotFullName = slotFullName,
    scanUrl = "$qrBaseUrl/p/$slotCode",
)
