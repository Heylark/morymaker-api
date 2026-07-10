package kr.co.morymaker.api.dto

/** 좌석 그룹 삭제 응답(§12-3 DELETE) — 삭제된 그룹 id만 반환(CheckoutResponse 패턴). */
data class SeatGroupDeleteResponse(val id: String)
