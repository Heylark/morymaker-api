package kr.co.morymaker.api.dto

import jakarta.validation.constraints.NotBlank

/**
 * 공개 kiosk 체크인 요청(KIO-04) — `guestId`만 받는다(KIO-02 선택 결과). token 경로는 이
 * REQ 범위에서 노출하지 않는다(D-D — QR 비밀 token 대신 guestId capability만 공개 표면에 노출).
 */
data class PublicKioskCheckinRequest(
    @field:NotBlank(message = "guestId는 필수입니다")
    val guestId: String,
)
