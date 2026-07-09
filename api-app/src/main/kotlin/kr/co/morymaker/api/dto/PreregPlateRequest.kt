package kr.co.morymaker.api.dto

import jakarta.validation.constraints.NotBlank

/** 차량 사전등록/수정 요청(§10-2). */
data class PreregPlateRequest(
    @field:NotBlank(message = "차량번호를 입력해 주세요")
    val plate: String,
)
