package kr.co.morymaker.api.dto

import jakarta.validation.constraints.NotBlank

/**
 * 공개 셀프 주차 등록 요청 DTO(§10-4). `token`(허브 경유 시 참석자 프리필용)은 body에 둔다
 * (spec §10-4 JSON 예시 기준 — auth-model의 `?token=` 쿼리 표기와의 불일치를 body로 확정).
 */
data class SelfParkRequest(
    @field:NotBlank(message = "차량번호를 입력해 주세요")
    val plate: String,
    val vipName: String? = null,
    val phone: String? = null,
    val token: String? = null,
)
