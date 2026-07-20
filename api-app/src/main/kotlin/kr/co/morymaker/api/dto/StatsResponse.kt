package kr.co.morymaker.api.dto

import kr.co.morymaker.api.application.port.`in`.StatsView

/** 통계 응답 DTO(§8-1 shape 1:1 — StatsView 병렬 유지, ZoneView→ZoneResponse 레이어 순수화 선례). */
data class StatsResponse(
    val registration: RegistrationResponse,
    val attendance: AttendanceResponse,
    val parking: ParkingResponse,
    val arrivals: List<ArrivalResponse>,
    val timeline: List<TimelineResponse>,
)

/** `preRatio`/`onRatio` = **구성비**(그룹 ÷ total). */
data class RegistrationResponse(
    val pre: Int,
    val on: Int,
    val total: Int,
    val preRatio: Double,
    val onRatio: Double,
)

/** `preRate`/`onRate`/`totRate` = **참석률**(실참석 ÷ 그룹 등록) — Ratio(구성비)와 필드명 구분(ADM-10). */
data class AttendanceResponse(
    val preAtt: Int,
    val onAtt: Int,
    val totAtt: Int,
    val preRate: Double,
    val onRate: Double,
    val totRate: Double,
)

data class ParkingResponse(val parked: Int, val byZone: List<ZoneOccupancyResponse>)

data class ZoneOccupancyResponse(
    val zoneId: String,
    val zoneName: String,
    val slotCount: Int,
    val occupied: Int,
    val reviewNeeded: Int,
)

data class ArrivalResponse(val guestId: String, val name: String, val visitAt: String)

data class TimelineResponse(val t: String, val cumulative: Int)

fun StatsView.toResponse(): StatsResponse = StatsResponse(
    registration = RegistrationResponse(
        pre = registration.pre, on = registration.on, total = registration.total,
        preRatio = registration.preRatio, onRatio = registration.onRatio,
    ),
    attendance = AttendanceResponse(
        preAtt = attendance.preAtt, onAtt = attendance.onAtt, totAtt = attendance.totAtt,
        preRate = attendance.preRate, onRate = attendance.onRate, totRate = attendance.totRate,
    ),
    parking = ParkingResponse(
        parked = parking.parked,
        byZone = parking.byZone.map {
            ZoneOccupancyResponse(
                zoneId = it.zoneId, zoneName = it.zoneName,
                slotCount = it.slotCount, occupied = it.occupied, reviewNeeded = it.reviewNeeded,
            )
        },
    ),
    arrivals = arrivals.map { ArrivalResponse(it.guestId, it.name, it.visitAt) },
    timeline = timeline.map { TimelineResponse(it.t, it.cumulative) },
)
