package kr.co.morymaker.api.dto

import jakarta.validation.constraints.NotBlank

/** 좌석 그룹 수정 요청 DTO(§12-3) — 라벨·numbering 토글만 변경 가능(groupNo·sortOrder 불변). */
data class SeatGroupUpdateRequest(
    @field:NotBlank(message = "라벨을 입력해 주세요")
    val label: String,
    val numbering: Boolean,
)
