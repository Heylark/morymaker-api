package kr.co.morymaker.api.dto

import jakarta.validation.constraints.NotBlank

/** 문자 미리보기 요청 DTO(§7-2a) — gid 단위 치환(이름 문자열 매칭 금지). */
data class SmsPreviewRequest(
    @field:NotBlank(message = "참석자를 선택해 주세요")
    val guestId: String,
)
