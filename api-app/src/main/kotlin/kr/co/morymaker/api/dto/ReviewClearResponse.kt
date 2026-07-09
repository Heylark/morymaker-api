package kr.co.morymaker.api.dto

/** 승계 확인 배지 해제 응답 DTO(§6-8). */
data class ReviewClearResponse(val id: String, val reviewNeeded: Boolean)
