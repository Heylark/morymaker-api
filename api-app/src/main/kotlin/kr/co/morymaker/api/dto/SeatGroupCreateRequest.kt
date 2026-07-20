package kr.co.morymaker.api.dto

import jakarta.validation.constraints.NotBlank

/** 좌석 그룹 등록 요청 DTO(§12-2). `label`만 필수 — `groupNo`·`sortOrder`는 서버 자동 채번. */
data class SeatGroupCreateRequest(
    @field:NotBlank(message = "라벨을 입력해 주세요")
    val label: String,
    val numbering: Boolean = false,
)
