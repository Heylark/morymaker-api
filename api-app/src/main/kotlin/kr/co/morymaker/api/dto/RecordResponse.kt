package kr.co.morymaker.api.dto

import kr.co.morymaker.api.domain.parking.ParkingRecord
import kr.co.morymaker.api.domain.parking.ParkingSlot
import java.time.Instant

/**
 * 주차 기록 응답 DTO(camelCase, §6-5 목록 + §6-6 등록 결과 공용 — GuestResponse 패턴).
 * `slotDisplay`는 [ParkingSlot.slotDisplay] 파생 편의 필드(응답 전용, DB 컬럼 아님).
 */
data class RecordResponse(
    val id: String,
    val zoneId: String,
    val slotSig: String,
    val slotDisplay: String,
    val plate: String,
    val phone: String?,
    val vipName: String?,
    val guestId: String?,
    val registeredBy: String,
    val registeredAt: Instant,
    val status: String,
    val reviewNeeded: Boolean,
)

fun ParkingRecord.toResponse(): RecordResponse = RecordResponse(
    id = id,
    zoneId = zoneId,
    slotSig = slotSig,
    slotDisplay = ParkingSlot.slotDisplay(slotSig),
    plate = plate,
    phone = phone,
    vipName = vipName,
    guestId = guestId,
    registeredBy = registeredBy,
    registeredAt = registeredAt,
    status = status,
    reviewNeeded = reviewNeeded,
)
