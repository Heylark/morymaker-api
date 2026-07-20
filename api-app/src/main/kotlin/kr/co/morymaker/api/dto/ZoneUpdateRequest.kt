package kr.co.morymaker.api.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

/**
 * 주차 구획 수정 요청 DTO(§6-3) — 구분 편집 + 자리 타이틀 일괄 저장.
 *
 * `titleOverrides`는 개별 수정분만 전달한다(§6-3 계약) — `null`이면 타이틀은 변경하지 않고,
 * 빈 맵을 포함해 값이 오면 zone_id 기준 전삭제 후 재삽입한다(delete-insert).
 */
data class ZoneUpdateRequest(
    @field:NotBlank(message = "구분1을 입력해 주세요")
    val part1: String,
    val part2: String? = null,
    val part3: String? = null,
    val part4: String? = null,
    val startNo: Int = 1,
    @field:Min(value = 1, message = "자리 개수는 1개 이상이어야 합니다")
    val slotCount: Int,
    val titleOverrides: Map<String, String>? = null,
)
