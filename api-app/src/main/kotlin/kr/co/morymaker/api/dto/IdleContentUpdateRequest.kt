package kr.co.morymaker.api.dto

/** 대기화면 콘텐츠 표시방식·순서 수정 요청 DTO(§11-4). */
data class IdleContentUpdateRequest(
    val mode: String? = null,
    val play: String? = null,
    val sortOrder: Int = 0,
)
