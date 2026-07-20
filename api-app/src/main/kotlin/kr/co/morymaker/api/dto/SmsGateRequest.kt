package kr.co.morymaker.api.dto

/** 발송 게이트 검증 요청 DTO(§7-3). */
data class SmsGateRequest(
    val excludeAlreadySent: Boolean = true,
)
