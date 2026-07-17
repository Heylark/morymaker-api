package kr.co.morymaker.api.dto

import jakarta.validation.constraints.NotBlank

/**
 * 대기화면 콘텐츠 등록 요청 DTO(§11-3) — multipart 폼 필드(M3, `file` 파트는 컨트롤러가 별도
 * `@RequestPart`로 수신). `@ModelAttribute` 생성자 바인딩 대상이라 필수 문자열 필드는 빈 문자열
 * 기본값 + `@NotBlank` 조합이어야 한다 — 기본값이 없으면 파트 누락 시 Kotlin 생성자 바인딩이
 * null을 받아 `@Valid` 도달 전에 catch-all 500으로 샌다(02-architect.md ADR-003 구현 제약).
 */
data class IdleContentCreateRequest(
    @field:NotBlank(message = "콘텐츠명을 입력해 주세요")
    val name: String = "",
    @field:NotBlank(message = "콘텐츠 종류를 입력해 주세요")
    val kind: String = "",
    val mode: String? = null,
    val play: String? = null,
    val sortOrder: Int = 0,
)
