package kr.co.morymaker.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import kr.co.morymaker.api.application.port.`in`.SeatGroupView

/**
 * 좌석 그룹 응답 DTO(camelCase, §12-1~3 공통 — ZoneResponse 패턴).
 * `slotCount`는 numbering OFF 그룹에서는 의미가 없어 키 자체를 생략한다(§12-1 응답 예시 정합).
 */
data class SeatGroupResponse(
    val id: String,
    val groupNo: Int,
    val label: String,
    val numbering: Boolean,
    val sortOrder: Int,
    val assignedCount: Int,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val slotCount: Int? = null,
)

fun SeatGroupView.toResponse(): SeatGroupResponse = SeatGroupResponse(
    id = id,
    groupNo = groupNo,
    label = label,
    numbering = numbering,
    sortOrder = sortOrder,
    assignedCount = assignedCount,
    slotCount = slotCount,
)
