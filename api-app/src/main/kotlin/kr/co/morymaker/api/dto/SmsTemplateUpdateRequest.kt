package kr.co.morymaker.api.dto

import jakarta.validation.constraints.NotBlank

/** 문자 템플릿 본문 수정 요청 DTO(§7-2). */
data class SmsTemplateUpdateRequest(
    @field:NotBlank(message = "본문을 입력해 주세요")
    val body: String,
)
