package kr.co.morymaker.api.dto

/**
 * 체크인 확정 요청(§5-1). `token`(QR/스캔) 또는 `guestId`(수동 선택) 중 하나 필수 — 둘 다 없으면
 * 컨트롤러가 `VALIDATION_FAILED`로 거부한다(02-architect §6-3).
 */
data class CheckinRequest(
    val token: String? = null,
    val guestId: String? = null,
)
