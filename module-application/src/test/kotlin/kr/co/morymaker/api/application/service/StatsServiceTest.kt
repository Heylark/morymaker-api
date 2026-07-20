package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.co.morymaker.api.application.port.out.ArrivalItem
import kr.co.morymaker.api.application.port.out.SrcRegistrationCount
import kr.co.morymaker.api.application.port.out.StatsPort
import kr.co.morymaker.api.application.port.out.TimelineBucket
import kr.co.morymaker.api.application.port.out.ZoneOccupancy
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.guest.Guest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * [StatsService] 단위 테스트 — [StatsPort]/[EventScopeGuard]를 mock으로 대체해 4블록 조립·
 * 산식(구성비/참석률)·timeline running cumulative·parked 합산만 검증한다.
 *
 * 전체 산식 TC(취소 제외·라벨 구분 전수)·cross-tenant 격리 TC는 Tester 담당.
 */
class StatsServiceTest {

    private val statsPort = mockk<StatsPort>()
    private val eventScopeGuard = mockk<EventScopeGuard>()
    private val service = StatsService(statsPort, eventScopeGuard)

    private fun stubEmptyPort(eventId: String = "ev1") {
        every { eventScopeGuard.assertAccess(eventId) } returns Unit
        every { statsPort.countRegistrationBySrc(eventId) } returns emptyList()
        every { statsPort.aggregateByZone(eventId) } returns emptyList()
        every { statsPort.aggregateTimeline(eventId) } returns emptyList()
        every { statsPort.selectArrivals(eventId) } returns emptyList()
    }

    @Test
    fun `getStats는 첫 줄에서 assertAccess를 호출한다`() {
        stubEmptyPort()

        service.getStats("ev1")

        verify(exactly = 1) { eventScopeGuard.assertAccess("ev1") }
    }

    @Test
    fun `registration은 구성비(그룹 대비)를, attendance는 참석률(등록 대비)을 각각 산출한다`() {
        stubEmptyPort()
        every { statsPort.countRegistrationBySrc("ev1") } returns listOf(
            SrcRegistrationCount(src = Guest.SRC_PRE, registered = 200, attended = 180),
            SrcRegistrationCount(src = Guest.SRC_ONSITE, registered = 100, attended = 80),
        )

        val result = service.getStats("ev1")

        // §8-1 예시 정합: 200/300→0.67, 100/300→0.33 (구성비=그룹÷total)
        assertEquals(200, result.registration.pre)
        assertEquals(100, result.registration.on)
        assertEquals(300, result.registration.total)
        assertEquals(0.67, result.registration.preRatio)
        assertEquals(0.33, result.registration.onRatio)

        // 180/200→0.90, 80/100→0.80, 260/300→0.87 (참석률=실참석÷그룹등록)
        assertEquals(180, result.attendance.preAtt)
        assertEquals(80, result.attendance.onAtt)
        assertEquals(260, result.attendance.totAtt)
        assertEquals(0.90, result.attendance.preRate)
        assertEquals(0.80, result.attendance.onRate)
        assertEquals(0.87, result.attendance.totRate)
    }

    @Test
    fun `없는 src는 행 자체가 없어 등록·참석 0으로 기본값 처리하고 분모 0은 비율 0으로 방어한다`() {
        stubEmptyPort()
        every { statsPort.countRegistrationBySrc("ev1") } returns emptyList() // 사전·현장 모두 미등록

        val result = service.getStats("ev1")

        assertEquals(0, result.registration.pre)
        assertEquals(0, result.registration.on)
        assertEquals(0, result.registration.total)
        assertEquals(0.0, result.registration.preRatio) // 분모 0 → 0.0(예외 아님)
        assertEquals(0.0, result.attendance.preRate)
    }

    @Test
    fun `parked는 byZone occupied 합이다`() {
        stubEmptyPort()
        every { statsPort.aggregateByZone("ev1") } returns listOf(
            ZoneOccupancy("z1", "지하 2층", "A구역", null, null, slotCount = 12, occupied = 5, reviewNeeded = 1),
            ZoneOccupancy("z2", "야외", null, null, null, slotCount = 8, occupied = 3, reviewNeeded = 0),
        )

        val result = service.getStats("ev1")

        assertEquals(8, result.parking.parked) // 5 + 3
        assertEquals("지하 2층 A구역", result.parking.byZone[0].zoneName) // ParkingSlot.zoneName 파생 재사용
        assertEquals("야외", result.parking.byZone[1].zoneName)
    }

    @Test
    fun `timeline은 시간버킷 count를 순서대로 누적한다`() {
        stubEmptyPort()
        every { statsPort.aggregateTimeline("ev1") } returns listOf(
            TimelineBucket("2026-07-10 17", "17시", count = 10),
            TimelineBucket("2026-07-10 18", "18시", count = 25),
            TimelineBucket("2026-07-10 19", "19시", count = 5),
        )

        val result = service.getStats("ev1")

        assertEquals(listOf("17시", "18시", "19시"), result.timeline.map { it.t })
        assertEquals(listOf(10, 35, 40), result.timeline.map { it.cumulative }) // running sum
    }

    @Test
    fun `arrivals는 포트 결과를 이름·도착시각 그대로 매핑한다`() {
        stubEmptyPort()
        every { statsPort.selectArrivals("ev1") } returns listOf(
            ArrivalItem(guestId = "g1", name = "홍길동", visitAt = "18:03"),
            ArrivalItem(guestId = "g2", name = "김철수", visitAt = "17:41"),
        )

        val result = service.getStats("ev1")

        assertEquals(2, result.arrivals.size)
        assertEquals("홍길동", result.arrivals[0].name)
        assertEquals("18:03", result.arrivals[0].visitAt)
    }
}
