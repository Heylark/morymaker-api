package kr.co.morymaker.api.persistence.adapter.guest

import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.GuestSearchQuery
import kr.co.morymaker.api.domain.guest.Guest
import kr.co.morymaker.api.persistence.mapper.GuestMapper
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.stereotype.Component
import java.sql.SQLException
import java.time.Instant

/**
 * [GuestPort] 구현체 — MyBatis 매퍼 위임.
 *
 * 헥사고날 레이어: Persistence(adapter). `internal`: application 계층은 [GuestPort]
 * 인터페이스만 의존한다.
 */
@Component
internal class GuestPersistenceAdapter(
    private val guestMapper: GuestMapper,
) : GuestPort {

    override fun fetchById(eventId: String, gid: String): Guest? = guestMapper.selectById(eventId, gid)

    override fun fetchDetailById(eventId: String, gid: String): GuestListItem? =
        guestMapper.selectDetailById(eventId, gid)

    override fun fetchDetailByToken(eventId: String, token: String): GuestListItem? =
        guestMapper.selectDetailByToken(eventId, token)

    override fun findByToken(token: String): GuestListItem? = guestMapper.selectDetailByGlobalToken(token)

    override fun search(eventId: String, query: GuestSearchQuery): List<GuestListItem> =
        guestMapper.search(eventId, query)

    override fun countSearch(eventId: String, query: GuestSearchQuery): Int =
        guestMapper.countSearch(eventId, query)

    override fun existsByToken(token: String): Boolean = guestMapper.existsByToken(token)

    override fun insert(guest: Guest) = guestMapper.insert(guest)

    override fun update(guest: Guest) = guestMapper.update(guest)

    override fun markAttendedIfNotAttended(eventId: String, gid: String, visitAt: Instant): Int =
        guardConcurrentCheckinRace { guestMapper.markAttendedIfNotAttended(eventId, gid, visitAt) }

    override fun markAttendedIfNotAttendedByToken(eventId: String, token: String, visitAt: Instant): Int =
        guardConcurrentCheckinRace { guestMapper.markAttendedIfNotAttendedByToken(eventId, token, visitAt) }

    /**
     * MariaDB InnoDB 스냅샷 격리(REPEATABLE READ, `innodb_snapshot_isolation`)는 두 트랜잭션이
     * 같은 행을 동시에 조건부 UPDATE로 경합할 때, 락 대기 끝에 뒤늦게 실행되는 쪽을 MySQL의
     * semi-consistent read처럼 최신 커밋값으로 조용히 재평가하지 않고 SQL 에러코드 1020("Record
     * has changed since last read")로 즉시 실패시킨다(실 DB 동시 체크인 검증에서 재현·확인 — 이
     * UPDATE가 트랜잭션의 첫 statement라도, 대상 행을 사전에 읽은 적이 없어도 발생한다). 이 실패는
     * 애플리케이션 관점에서 "내가 이 행을 갱신하지 못했다"(경쟁 패배)와 의미가 완전히 같으므로
     * affected rows 0으로 정규화한다 — 이 예외를 던진 트랜잭션 자체는 여전히 유효해 이어지는
     * 조회로 최신 상태를 안전하게 다시 읽을 수 있다(동일 실 DB 검증으로 확인). [GuestPort]의
     * 조건부 전이 계약("반환값으로 전이 확정 여부를 판정")을 이 어댑터 경계에서 지켜내는
     * 인프라 특이 케이스 흡수 지점 — 상위 application 계층은 이 MariaDB 특성을 알 필요가 없다.
     */
    private fun guardConcurrentCheckinRace(update: () -> Int): Int =
        try {
            update()
        } catch (e: UncategorizedSQLException) {
            val cause = e.cause as? SQLException
            if (cause?.errorCode == CHECKIN_RACE_LOST_SQL_ERROR_CODE) 0 else throw e
        }

    companion object {
        private const val CHECKIN_RACE_LOST_SQL_ERROR_CODE = 1020
    }
}
