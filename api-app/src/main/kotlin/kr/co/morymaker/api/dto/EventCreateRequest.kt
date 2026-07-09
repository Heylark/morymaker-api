package kr.co.morymaker.api.dto

import jakarta.validation.constraints.NotBlank
import java.time.Instant

/** 행사 생성 요청 DTO(02-api-spec §2-2). `name`만 필수 — 나머지는 미지정 시 브랜드 기본값 상속. */
data class EventCreateRequest(
    @field:NotBlank(message = "행사명을 입력해 주세요")
    val name: String,
    val eventDate: Instant? = null,
    val place: String? = null,
    val type: String? = null,
    val bgColor: String? = null,
    val pointColor: String? = null,
    val titleColor: String? = null,
    val bodyColor: String? = null,
    val kv: String? = null,
)
