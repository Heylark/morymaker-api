package kr.co.morymaker.api.dto

import kr.co.morymaker.api.application.port.`in`.PublicHubResult
import kr.co.morymaker.api.config.PublicProperties
import java.time.Instant

/**
 * 개인 허브 응답(§10-1) — 초대장·체크인QR·사전차량·주차진입 4요소를 한 응답에 담는다.
 *
 * `guest` 필드는 1차로 마스킹하지 않고 원문 그대로 노출한다(스펙 응답 예시와 동일 정책) —
 * 모리메이커 마스킹 정책이 확정되면 그 시점에 응답 필드 축소로 대응할 수 있는 결정이다.
 */
data class PublicHubResponse(
    val guest: PublicGuestView,
    val event: PublicEventView,
    val checkinQr: PublicCheckinQrView,
    val prereg: PublicPreregView,
    val parkingEntry: PublicParkingEntryView,
)

data class PublicGuestView(
    val name: String,
    val org: String?,
    val title: String?,
    val seatLabel: String?,
    val plate: String?,
    val status: String,
)

data class PublicEventView(
    val name: String,
    val place: String?,
    val eventDate: Instant?,
    val bgColor: String?,
    val pointColor: String?,
)

/** 체크인 QR 표시용(§5-1만 참석 확정 — 이 값 조회 자체는 체크인이 아니다). */
data class PublicCheckinQrView(val url: String, val token: String)

data class PublicPreregView(val plateRegistered: Boolean, val plate: String?)

/** 자리 딥링크 기능(후속 작업)의 실데이터 의존 없이 정적 config 값만 노출한다. */
data class PublicParkingEntryView(val scanUrl: String)

fun PublicHubResult.toResponse(props: PublicProperties): PublicHubResponse = PublicHubResponse(
    guest = PublicGuestView(
        name = guest.name,
        org = guest.org,
        title = guest.title,
        seatLabel = guest.seatLabel,
        plate = guest.plate,
        status = guest.status,
    ),
    event = PublicEventView(
        name = event.name,
        place = event.place,
        eventDate = event.eventDate,
        bgColor = event.bgColor,
        pointColor = event.pointColor,
    ),
    checkinQr = PublicCheckinQrView(url = "${props.eventBaseUrl}/u/${guest.token}", token = guest.token),
    prereg = PublicPreregView(plateRegistered = !guest.plate.isNullOrBlank(), plate = guest.plate),
    parkingEntry = PublicParkingEntryView(scanUrl = props.parkScanUrl),
)
