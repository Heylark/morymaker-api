package kr.co.morymaker.api.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

/** 주차 구획 등록 요청 DTO(§6-2). `part1`만 필수 — 자리는 `startNo`부터 `slotCount`개 논리 생성. */
data class ZoneCreateRequest(
    @field:NotBlank(message = "구분1을 입력해 주세요")
    val part1: String,
    val part2: String? = null,
    val part3: String? = null,
    val part4: String? = null,
    val startNo: Int = 1,
    @field:Min(value = 1, message = "자리 개수는 1개 이상이어야 합니다")
    val slotCount: Int,
)
