package kr.co.morymaker.api.dto

import jakarta.validation.constraints.NotBlank

/** 참석자 개별 등록 요청 DTO(§4-2). `name`만 필수 — `src` 미지정 시 현장 등록으로 간주. */
data class GuestCreateRequest(
    @field:NotBlank(message = "이름을 입력해 주세요")
    val name: String,
    val org: String? = null,
    val title: String? = null,
    val phone: String? = null,
    val plate: String? = null,
    val seatGroupId: String? = null,
    val src: String? = null,
)
