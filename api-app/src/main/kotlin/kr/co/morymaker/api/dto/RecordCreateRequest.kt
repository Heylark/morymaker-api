package kr.co.morymaker.api.dto

import jakarta.validation.constraints.NotBlank

/** 주차 기록 등록 요청 DTO(§6-6) — 요원(PRK-02)·셀프(§10 후속)가 공유하는 코어 계약. */
data class RecordCreateRequest(
    @field:NotBlank(message = "자리를 선택해 주세요")
    val slotSig: String,
    @field:NotBlank(message = "구획을 선택해 주세요")
    val zoneId: String,
    @field:NotBlank(message = "차량번호를 입력해 주세요")
    val plate: String,
    val phone: String? = null,
    val vipName: String? = null,
    @field:NotBlank(message = "등록 주체를 선택해 주세요")
    val registeredBy: String,
)
