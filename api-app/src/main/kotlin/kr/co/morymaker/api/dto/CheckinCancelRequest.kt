package kr.co.morymaker.api.dto

import jakarta.validation.constraints.NotBlank

/** 체크인 취소 요청(§5-3) — 참석→대기로 되돌릴 대상 지정. */
data class CheckinCancelRequest(
    @field:NotBlank(message = "guestId를 입력해 주세요")
    val guestId: String,
)
