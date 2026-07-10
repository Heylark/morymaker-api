package kr.co.morymaker.api.dto

import kr.co.morymaker.api.application.port.`in`.SlotView

/** 자리 QR 응답 DTO — scanUrl은 qrZip이 인코딩하는 QR payload와 동일 조립식을 공유한다(단일 진실 소스). 관리자 콘솔의 QR 미리보기 화면이 이 값을 그대로 인코딩해 렌더링한다. */
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
