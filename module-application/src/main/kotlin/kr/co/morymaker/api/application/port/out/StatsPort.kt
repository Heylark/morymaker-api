package kr.co.morymaker.api.application.port.out

/**
 * 통계 집계 영속 포트-out(§8) — module-persistence의 `StatsPersistenceAdapter`가 구현한다.
 *
 * guest·parking_zone·parking_record를 **읽기만** 한다 — 동결 대상(GuestMapper·ParkingZoneMapper·
 * ParkingRecordMapper)에는 신규 메서드를 추가하지 않고, 전용 `StatsMapper`로만 집계한다
 * (02-architect §2 동결 경계).
 */
interface StatsPort {

    /** src(사전/현장)별 등록·실참석 수(취소 제외). 없는 src는 행 자체가 없음 → 서비스가 0 기본값. */
    fun countRegistrationBySrc(eventId: String): List<SrcRegistrationCount>

    /** 구획별 점유(활성 주차중 기준) — 모든 구획 행 반환(빈 구획도 0). */
    fun aggregateByZone(eventId: String): List<ZoneOccupancy>

    /** 시간버킷별 체크인(status=참석) 건수 — bucketKey 오름차순. */
    fun aggregateTimeline(eventId: String): List<TimelineBucket>

    /** 도착순(visit_at 존재·취소 제외) — visit_at 내림차순. */
    fun selectArrivals(eventId: String): List<ArrivalItem>
}

data class SrcRegistrationCount(val src: String, val registered: Int, val attended: Int)

data class ZoneOccupancy(
    val zoneId: String,
    val part1: String?,
    val part2: String?,
    val part3: String?,
    val part4: String?,
    val slotCount: Int,
    val occupied: Int,
    val reviewNeeded: Int,
)

data class TimelineBucket(val bucketKey: String, val label: String, val count: Int)

/** visitAt = "HH:mm"(SQL 포맷 문자열 — Kotlin TZ/포맷 로직 0). */
data class ArrivalItem(val guestId: String, val name: String, val visitAt: String)
