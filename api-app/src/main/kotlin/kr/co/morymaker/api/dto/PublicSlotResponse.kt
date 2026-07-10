package kr.co.morymaker.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import kr.co.morymaker.api.application.port.`in`.PublicSlotView

/**
 * 공개 자리 상태 응답 DTO(§10-3) — viewType 분기 결과. `OCCUPIED_NOTICE`도 동일 shape을 쓴다 —
 * 타인정보(plate·vipName)는 이 shape에 애초에 필드가 없어 구조적으로 미노출된다(마스킹 로직 불요).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PublicSlotResponse(
    val slot: SlotView,
    val viewType: String,
    val occupied: Boolean,
    val event: EventBrandingView,
)

data class SlotView(val slotCode: String, val slotSig: String, val display: String)

data class EventBrandingView(val name: String, val bgColor: String?, val pointColor: String?)

fun PublicSlotView.toResponse(): PublicSlotResponse = PublicSlotResponse(
    slot = SlotView(slotCode = slotCode, slotSig = slotSig, display = display),
    viewType = viewType,
    occupied = occupied,
    event = EventBrandingView(name = event.name, bgColor = event.bgColor, pointColor = event.pointColor),
)
