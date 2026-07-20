package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.ArrivalView
import kr.co.morymaker.api.application.port.`in`.AttendanceView
import kr.co.morymaker.api.application.port.`in`.RegistrationView
import kr.co.morymaker.api.application.port.`in`.StatsParkingView
import kr.co.morymaker.api.application.port.`in`.StatsUseCase
import kr.co.morymaker.api.application.port.`in`.StatsView
import kr.co.morymaker.api.application.port.`in`.TimelineView
import kr.co.morymaker.api.application.port.`in`.ZoneOccupancyView
import kr.co.morymaker.api.application.port.out.StatsPort
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.guest.Guest
import kr.co.morymaker.api.domain.parking.ParkingSlot
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * [StatsUseCase] 구현체 — 집계 4블록(registration·attendance·parking·arrivals·timeline)을
 * 조립한다(§8-1). export(§8-2)는 컨트롤러가 이 결과를 재사용한다.
 *
 * 헥사고날 레이어: application(service). `internal`: api-app은 [StatsUseCase] 인터페이스만
 * 의존한다. 쓰기 0 — 전 메서드 읽기 전용.
 */
@Service
internal class StatsService(
    private val statsPort: StatsPort,
    private val eventScopeGuard: EventScopeGuard,
) : StatsUseCase {

    @Transactional(readOnly = true)
    override fun getStats(eventId: String): StatsView {
        eventScopeGuard.assertAccess(eventId) // ← 첫 줄 필수(Layer2b)

        // 1) registration + attendance (src별 등록/참석)
        val bySrc = statsPort.countRegistrationBySrc(eventId)
        val pre = bySrc.firstOrNull { it.src == Guest.SRC_PRE }
        val on = bySrc.firstOrNull { it.src == Guest.SRC_ONSITE }
        val preReg = pre?.registered ?: 0
        val onReg = on?.registered ?: 0
        val preAtt = pre?.attended ?: 0
        val onAtt = on?.attended ?: 0
        val totalReg = preReg + onReg
        val totAtt = preAtt + onAtt

        val registration = RegistrationView(
            pre = preReg, on = onReg, total = totalReg,
            preRatio = ratio(preReg, totalReg), onRatio = ratio(onReg, totalReg), // 구성비 = 그룹 ÷ total
        )
        val attendance = AttendanceView(
            preAtt = preAtt, onAtt = onAtt, totAtt = totAtt,
            preRate = ratio(preAtt, preReg), // 참석률 = 실참석 ÷ 그룹등록
            onRate = ratio(onAtt, onReg),
            totRate = ratio(totAtt, totalReg),
        )

        // 2) parking (byZone + parked = occupied 합)
        val zones = statsPort.aggregateByZone(eventId).map {
            ZoneOccupancyView(
                zoneId = it.zoneId,
                zoneName = ParkingSlot.zoneName(it.part1, it.part2, it.part3, it.part4), // 파생 재사용
                slotCount = it.slotCount, occupied = it.occupied, reviewNeeded = it.reviewNeeded,
            )
        }
        val parking = StatsParkingView(parked = zones.sumOf { it.occupied }, byZone = zones)

        // 3) arrivals
        val arrivals = statsPort.selectArrivals(eventId)
            .map { ArrivalView(it.guestId, it.name, it.visitAt) }

        // 4) timeline (running cumulative)
        var acc = 0
        val timeline = statsPort.aggregateTimeline(eventId).map {
            acc += it.count
            TimelineView(t = it.label, cumulative = acc)
        }

        return StatsView(registration, attendance, parking, arrivals, timeline)
    }

    /** 분모 0 방어 + 소수 2자리 반올림(HALF_UP) — §8-1 예시(0.67·0.90) 정합. */
    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0
        else BigDecimal(numerator).divide(BigDecimal(denominator), 2, RoundingMode.HALF_UP).toDouble()
}
