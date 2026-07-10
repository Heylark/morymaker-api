package kr.co.morymaker.api.dto

import jakarta.validation.constraints.Pattern

private const val HEX_COLOR_REGEX = "^#[0-9a-fA-F]{6}$"
private const val HEX_COLOR_MESSAGE = "색상은 #RRGGBB 형식으로 입력해 주세요"

/**
 * 브랜딩 명시 저장 요청 DTO(§11-1, ADM-04). 컬러 4종(bg/point/title/body) + kv + defaultIdleMode —
 * 미리보기는 프론트 메모리에서만 유지되고, 이 요청만 서비스에 실제 반영된다(라이브 키오스크 실수
 * 반영 방지). `@Pattern`은 값이 null이면 검증을 통과한다(Bean Validation 규약) — 미지정 필드 허용.
 */
data class EventBrandingUpdateRequest(
    @field:Pattern(regexp = HEX_COLOR_REGEX, message = HEX_COLOR_MESSAGE)
    val bgColor: String? = null,
    @field:Pattern(regexp = HEX_COLOR_REGEX, message = HEX_COLOR_MESSAGE)
    val pointColor: String? = null,
    @field:Pattern(regexp = HEX_COLOR_REGEX, message = HEX_COLOR_MESSAGE)
    val titleColor: String? = null,
    @field:Pattern(regexp = HEX_COLOR_REGEX, message = HEX_COLOR_MESSAGE)
    val bodyColor: String? = null,
    val kv: String? = null,
    val defaultIdleMode: String? = null,
)
