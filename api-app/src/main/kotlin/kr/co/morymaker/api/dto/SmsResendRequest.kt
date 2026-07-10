package kr.co.morymaker.api.dto

import jakarta.validation.constraints.NotBlank

/** 개별 재발송 요청 DTO(§7-5) — confirm=true 필수(미충족 시 서비스가 400). */
data class SmsResendRequest(
    @field:NotBlank(message = "참석자를 선택해 주세요")
    val guestId: String,
    val confirm: Boolean = false,
)
