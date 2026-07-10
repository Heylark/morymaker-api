package kr.co.morymaker.api.dto

import jakarta.validation.constraints.NotBlank
import java.time.Instant

/**
 * 행사 정보·상태 수정 요청 DTO(§2-4). 브랜딩 컬러 4종·defaultIdleMode는 포함하지 않는다 —
 * 저장 게이트(ADM-04) 전용 경로는 [EventBrandingUpdateRequest]로 분리돼 있다.
 */
data class EventUpdateRequest(
    @field:NotBlank(message = "행사명을 입력해 주세요")
    val name: String,
    val eventDate: Instant? = null,
    val place: String? = null,
    val type: String? = null,
    val kv: String? = null,
    val status: String,
    val active: Boolean,
)
