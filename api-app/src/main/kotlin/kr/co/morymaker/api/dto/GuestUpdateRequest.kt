package kr.co.morymaker.api.dto

/** 참석자 부분 수정 요청 DTO(§4-3). null 필드는 미변경(부분 갱신) — 상태 되돌리기는 §5-3 분리. */
data class GuestUpdateRequest(
    val name: String? = null,
    val org: String? = null,
    val title: String? = null,
    val phone: String? = null,
    val plate: String? = null,
    val seatGroupId: String? = null,
)
