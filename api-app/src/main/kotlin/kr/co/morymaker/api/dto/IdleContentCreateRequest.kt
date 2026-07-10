package kr.co.morymaker.api.dto

import jakarta.validation.constraints.NotBlank

/** 대기화면 콘텐츠 등록 요청 DTO(§11-3) — 메타 전용(file part 없음, M2 — multipart 미도입). */
data class IdleContentCreateRequest(
    @field:NotBlank(message = "콘텐츠명을 입력해 주세요")
    val name: String,
    @field:NotBlank(message = "콘텐츠 종류를 입력해 주세요")
    val kind: String,
    val mode: String? = null,
    val play: String? = null,
    val sortOrder: Int = 0,
)
