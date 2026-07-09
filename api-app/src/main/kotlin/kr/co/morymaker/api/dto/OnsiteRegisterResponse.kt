package kr.co.morymaker.api.dto

import kr.co.morymaker.api.config.PublicProperties
import kr.co.morymaker.api.domain.guest.Guest

/** 현장등록 실행 응답(§10-6) — 명단 추가와 함께 체크인 QR을 즉시 발급한다. */
data class OnsiteRegisterResponse(
    val guestId: String,
    val token: String,
    val checkinQr: PublicCheckinQrView,
    val message: String,
)

fun Guest.toOnsiteRegisterResponse(props: PublicProperties): OnsiteRegisterResponse = OnsiteRegisterResponse(
    guestId = id,
    token = token,
    checkinQr = PublicCheckinQrView(url = "${props.eventBaseUrl}/u/$token", token = token),
    message = "등록 완료 — 데스크에서 QR을 스캔하면 참석 처리됩니다",
)
