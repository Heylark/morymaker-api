package kr.co.morymaker.api.dto

/** 좌석 일괄 배정 요청 DTO(§12-5). `assignments`가 그룹의 배정 세트 전체를 대체한다(원자 교체). */
data class SeatAssignmentBulkUpdateRequest(
    val groupNo: Int,
    val assignments: List<SeatAssignmentEntryRequest> = emptyList(),
)

/** 배정 항목 1건 — `ord`는 numbering OFF 그룹에서는 서버가 무시하고 9999로 강제한다. */
data class SeatAssignmentEntryRequest(
    val ord: Int,
    val guestId: String? = null,
)
