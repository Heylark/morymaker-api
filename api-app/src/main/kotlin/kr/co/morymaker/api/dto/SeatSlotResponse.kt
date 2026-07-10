package kr.co.morymaker.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import kr.co.morymaker.api.application.port.`in`.SeatSlotView

/**
 * 좌석 배정 슬롯 응답 DTO(§12-4·§12-5 공용). `guestId`는 빈 좌석(null)도 항상 노출하되
 * `guestName`·`empty`는 값이 있을 때만 노출한다(§12-4 응답 예시 정합).
 */
data class SeatSlotResponse(
    val id: String,
    val groupNo: Int,
    val ord: Int,
    val guestId: String?,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val guestName: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val empty: Boolean? = null,
)

fun SeatSlotView.toResponse(): SeatSlotResponse = SeatSlotResponse(
    id = id,
    groupNo = groupNo,
    ord = ord,
    guestId = guestId,
    guestName = guestName,
    empty = empty,
)
