package kr.co.morymaker.api.dto

import jakarta.validation.constraints.NotBlank

/** 현장등록 실행 요청(§10-6). `name`만 필수 — 연락처도 수집한다(dev-default, 사용자 CP-1 확정). */
data class OnsiteRegisterRequest(
    @field:NotBlank(message = "이름을 입력해 주세요")
    val name: String,
    val org: String? = null,
    val phone: String? = null,
    val plate: String? = null,
)
