package kr.co.morymaker.api.dto

import jakarta.validation.constraints.NotBlank

/**
 * 공개 kiosk 체크인 요청(KIO-04) — `guestId`만 받는다(KIO-02 선택 결과). QR 스캔용 비밀 token
 * 경로는 공개 표면에 노출하지 않는다 — guestId(랜덤 UUID·비열거·PII 아님) capability만 노출한다.
 */
data class PublicKioskCheckinRequest(
    @field:NotBlank(message = "guestId는 필수입니다")
    val guestId: String,
)
