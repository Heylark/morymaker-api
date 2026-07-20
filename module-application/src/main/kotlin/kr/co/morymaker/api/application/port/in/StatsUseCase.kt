package kr.co.morymaker.api.application.port.`in`

/**
 * 통계 유스케이스 포트-in(§8) — api-app의 `StatsController`가 호출한다.
 *
 * `GET /stats`·`GET /stats/export` 모두 이 단일 메서드를 호출한다 — export는 동일 집계
 * 결과([StatsView])를 재사용해 별도 쿼리를 만들지 않는다(02-architect ADR-S3).
 */
interface StatsUseCase {

    /** 집계 4블록 조립 — 첫 줄에서 `assertAccess(eventId)`로 행사 스코프를 검증한다. */
    fun getStats(eventId: String): StatsView
}

/** [StatsUseCase.getStats] 결과 — §8-1 응답 shape과 1:1 대응하는 read model. */
data class StatsView(
    val registration: RegistrationView,
    val attendance: AttendanceView,
    val parking: StatsParkingView,
    val arrivals: List<ArrivalView>,
    val timeline: List<TimelineView>,
)

/** 등록 현황 — `preRatio`/`onRatio`는 **구성비**(그룹 ÷ total). registration에 totRatio는 없다(spec 정합). */
data class RegistrationView(
    val pre: Int,
    val on: Int,
    val total: Int,
    val preRatio: Double,
    val onRatio: Double,
)

/** 참석 현황 — `preRate`/`onRate`/`totRate`는 **참석률**(실참석 ÷ 그룹 등록). Ratio(구성비)와 필드명으로 구분(ADM-10). */
data class AttendanceView(
    val preAtt: Int,
    val onAtt: Int,
    val totAtt: Int,
    val preRate: Double,
    val onRate: Double,
    val totRate: Double,
)

// "ParkingView" 이름은 CheckinUseCase.kt(체크인 응답의 주차 위치 표시)가 같은 패키지에서 이미
// 선점 — 통계 집계용 별개 개념이라 접두사로 구분한다.
data class StatsParkingView(val parked: Int, val byZone: List<ZoneOccupancyView>)

/** `slotCount`(구획 총 자리 수)는 좌석 정원이 아니다(§8-1 주석 정합). */
data class ZoneOccupancyView(
    val zoneId: String,
    val zoneName: String,
    val slotCount: Int,
    val occupied: Int,
    val reviewNeeded: Int,
)

data class ArrivalView(val guestId: String, val name: String, val visitAt: String)

/** timeline 시간버킷 — `cumulative`는 참석(status=참석) 대상 running 누적(02-architect ADR-S1). */
data class TimelineView(val t: String, val cumulative: Int)
