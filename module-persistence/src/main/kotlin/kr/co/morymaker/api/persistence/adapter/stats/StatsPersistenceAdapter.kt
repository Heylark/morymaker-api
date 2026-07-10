package kr.co.morymaker.api.persistence.adapter.stats

import kr.co.morymaker.api.application.port.out.ArrivalItem
import kr.co.morymaker.api.application.port.out.SrcRegistrationCount
import kr.co.morymaker.api.application.port.out.StatsPort
import kr.co.morymaker.api.application.port.out.TimelineBucket
import kr.co.morymaker.api.application.port.out.ZoneOccupancy
import kr.co.morymaker.api.persistence.mapper.StatsMapper
import org.springframework.stereotype.Component

/**
 * [StatsPort] 구현체 — thin delegate. 헥사고날 레이어: Persistence(adapter). `internal`:
 * application 계층은 [StatsPort] 인터페이스만 의존한다.
 */
@Component
internal class StatsPersistenceAdapter(
    private val statsMapper: StatsMapper,
) : StatsPort {

    override fun countRegistrationBySrc(eventId: String): List<SrcRegistrationCount> =
        statsMapper.countRegistrationBySrc(eventId)

    override fun aggregateByZone(eventId: String): List<ZoneOccupancy> =
        statsMapper.aggregateByZone(eventId)

    override fun aggregateTimeline(eventId: String): List<TimelineBucket> =
        statsMapper.aggregateTimeline(eventId)

    override fun selectArrivals(eventId: String): List<ArrivalItem> =
        statsMapper.selectArrivals(eventId)
}
