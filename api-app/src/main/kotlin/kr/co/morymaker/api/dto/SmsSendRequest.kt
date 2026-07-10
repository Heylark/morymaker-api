package kr.co.morymaker.api.dto

/** 초대 문자 일괄 발송 요청 DTO(§7-4) — confirm=true 필수(미충족 시 서비스가 400). */
data class SmsSendRequest(
    val excludeAlreadySent: Boolean = true,
    val confirm: Boolean = false,
)
