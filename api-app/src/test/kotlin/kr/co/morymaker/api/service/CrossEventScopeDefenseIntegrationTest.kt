package kr.co.morymaker.api.service

import kr.co.morymaker.api.application.port.`in`.AssignmentEntry
import kr.co.morymaker.api.application.port.`in`.BulkAssignCommand
import kr.co.morymaker.api.application.port.`in`.CreateEventCommand
import kr.co.morymaker.api.application.port.`in`.EventUseCase
import kr.co.morymaker.api.application.port.`in`.GuestUseCase
import kr.co.morymaker.api.application.port.`in`.ParkingRecordUseCase
import kr.co.morymaker.api.application.port.`in`.ParkingZoneUseCase
import kr.co.morymaker.api.application.port.`in`.RegisterGuestCommand
import kr.co.morymaker.api.application.port.`in`.RegisterParkingCommand
import kr.co.morymaker.api.application.port.`in`.SeatAssignmentUseCase
import kr.co.morymaker.api.application.port.`in`.SeatGroupCreateCommand
import kr.co.morymaker.api.application.port.`in`.SeatGroupUseCase
import kr.co.morymaker.api.application.port.`in`.ZoneCreateCommand
import kr.co.morymaker.api.application.port.`in`.ZoneUpdateCommand
import kr.co.morymaker.api.domain.guest.Guest
import kr.co.morymaker.api.domain.parking.ParkingRecord
import kr.co.morymaker.api.persistence.mapper.GuestMapper
import kr.co.morymaker.api.persistence.mapper.ParkingRecordMapper
import kr.co.morymaker.api.persistence.mapper.ParkingSlotTitleMapper
import kr.co.morymaker.api.persistence.mapper.SeatAssignmentMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * cross-event 격리 방어심층(belt-and-suspenders) 실 DB 통합 테스트.
 *
 * `GuestMapper`·`ParkingRecordMapper`·`SeatAssignmentMapper`·`ParkingSlotTitleMapper` 4개 매퍼
 * 빈을 서비스 레이어(포트·어댑터·`EventScopeGuard`) 우회하여 **직접** 호출한다 — 이 REQ의 목적이
 * "서비스 가드 없이 재사용하는 신규 호출자"로부터의 방어이므로, 서비스를 거치면 가드가 항상
 * 먼저 막아 SQL 자체의 방어선을 검증할 수 없다.
 *
 * 공통 패턴(12개 대상 전부 동일 — 02-architect §6 "정상 대비 실증"): 리소스는 항상 **피해자
 * 행사(B)**에 생성한다. ① 공격 호출 = `eventId=공격자 행사(A)` + `id=피해자(B) 소속 리소스 id` →
 * 상태 불변(0행) 실증. ② 정상 호출 = 같은 리소스를 `eventId=피해자 행사(B)`(실소유)로 호출 →
 * 상태 변경(1행) 실증. 동일 리소스에 대한 쌍 검증이라 "조건이 동어반복이 아님"(mutation
 * efficacy 정신)을 함께 증명한다.
 *
 * mapper 인터페이스 update/delete 메서드는 반환형이 `Unit`이다(affected-rows 미노출 —
 * 설계상 12개 전부 affected-rows 무의존과 정합). 따라서 상태 검증은 매퍼 반환값이
 * 아니라 `DataSource` raw SQL로 컬럼을 직접 SELECT하는 방식만 가능하다(독립 검증 경로).
 */
@SpringBootTest
class CrossEventScopeDefenseIntegrationTest(
    @Autowired private val eventUseCase: EventUseCase,
    @Autowired private val guestUseCase: GuestUseCase,
    @Autowired private val zoneUseCase: ParkingZoneUseCase,
    @Autowired private val recordUseCase: ParkingRecordUseCase,
    @Autowired private val groupUseCase: SeatGroupUseCase,
    @Autowired private val assignmentUseCase: SeatAssignmentUseCase,
    @Autowired private val guestMapper: GuestMapper,
    @Autowired private val parkingRecordMapper: ParkingRecordMapper,
    @Autowired private val seatAssignmentMapper: SeatAssignmentMapper,
    @Autowired private val parkingSlotTitleMapper: ParkingSlotTitleMapper,
    @Autowired private val dataSource: DataSource,
) {

    private val createdEventIds = mutableListOf<String>()

    @AfterEach
    fun cleanup() {
        SecurityContextHolder.clearContext()
        dataSource.connection.use { conn ->
            createdEventIds.forEach { id ->
                conn.prepareStatement("DELETE FROM event WHERE id = ?").use { stmt ->
                    stmt.setString(1, id)
                    stmt.executeUpdate()
                }
            }
        }
        createdEventIds.clear()
    }

    private fun systemAdminJwt(): Jwt = Jwt.withTokenValue("dummy-token")
        .header("alg", "RS256")
        .claim("iss", "http://localhost:30000")
        .claim("roles", listOf("SYSTEM_ADMIN"))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(3600))
        .build()

    private fun <T> asSystemAdmin(block: () -> T): T {
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(systemAdminJwt())
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun createEvent(name: String): String {
        val id = asSystemAdmin {
            eventUseCase.createEvent(
                CreateEventCommand(
                    name = name, eventDate = null, place = null, type = null,
                    bgColor = null, pointColor = null, titleColor = null, bodyColor = null, kv = null,
                ),
            ).id
        }
        createdEventIds += id
        return id
    }

    /** 공격자(attacker)·피해자(victim) 두 행사 — 매 테스트 독립 생성(cleanup에서 CASCADE 정리). */
    private fun twoEvents(): Pair<String, String> =
        createEvent("cross-event 공격자 행사") to createEvent("cross-event 피해자 행사")

    private fun mkGuest(eid: String, name: String = "피해자 참석자"): Guest = asSystemAdmin {
        guestUseCase.registerGuest(
            eid,
            RegisterGuestCommand(name = name, org = null, title = null, phone = null, plate = null, seatGroupId = null),
        )
    }

    private fun mkZone(eid: String): String = asSystemAdmin {
        zoneUseCase.createZone(
            eid,
            ZoneCreateCommand(part1 = "지하 1층", part2 = "테스트구역", part3 = null, part4 = null, startNo = 1, slotCount = 5),
        ).id
    }

    private fun mkRecord(eid: String, zoneId: String, slotSig: String, plate: String): ParkingRecord = asSystemAdmin {
        recordUseCase.register(
            eid,
            RegisterParkingCommand(slotSig = slotSig, zoneId = zoneId, plate = plate, phone = null, vipName = null, registeredBy = "요원"),
        ).record
    }

    private fun mkGroup(eid: String, label: String = "테스트조"): String = asSystemAdmin {
        groupUseCase.createGroup(eid, SeatGroupCreateCommand(label = label, numbering = true)).id
    }

    // ── raw JDBC 상태 검증 헬퍼 ──────────────────────────────────────

    private fun queryString(sql: String, vararg params: String): String? = dataSource.connection.use { conn ->
        conn.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { i, p -> stmt.setString(i + 1, p) }
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    private fun queryInt(sql: String, vararg params: String): Int? = dataSource.connection.use { conn ->
        conn.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { i, p -> stmt.setString(i + 1, p) }
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
        }
    }

    private fun execUpdate(sql: String, vararg params: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { i, p -> stmt.setString(i + 1, p) }
                stmt.executeUpdate()
            }
        }
    }

    // ── #1 GuestMapper.markVisitedIfWaiting ─────────────────────────

    @Test
    fun `markVisitedIfWaiting은 위조 eventId로 0행, 소유 eventId로 1행 매칭된다`() {
        val (attacker, victim) = twoEvents()
        val guest = mkGuest(victim)

        guestMapper.markVisitedIfWaiting(attacker, guest.id)
        assertEquals(Guest.STATUS_WAITING, queryString("SELECT status FROM guest WHERE id = ?", guest.id), "공격 호출 후 상태가 그대로여야 한다(0행 차단)")

        guestMapper.markVisitedIfWaiting(victim, guest.id)
        assertEquals(Guest.STATUS_VISITED, queryString("SELECT status FROM guest WHERE id = ?", guest.id), "소유 행사 호출은 정상 전이해야 한다(1행)")
    }

    // ── #2 GuestMapper.backfillPlateIfEmpty ──────────────────────────

    @Test
    fun `backfillPlateIfEmpty는 위조 eventId로 0행, 소유 eventId로 1행 매칭된다`() {
        val (attacker, victim) = twoEvents()
        val guest = mkGuest(victim)

        guestMapper.backfillPlateIfEmpty(attacker, guest.id, "99하9999")
        assertNull(queryString("SELECT plate FROM guest WHERE id = ?", guest.id), "공격 호출 후 plate는 여전히 비어 있어야 한다")

        guestMapper.backfillPlateIfEmpty(victim, guest.id, "99하9999")
        assertEquals("99하9999", queryString("SELECT plate FROM guest WHERE id = ?", guest.id), "소유 행사 호출은 plate를 백필해야 한다")
    }

    // ── #3 GuestMapper.updateSeatGroupId ─────────────────────────────

    @Test
    fun `updateSeatGroupId는 위조 eventId로 0행, 소유 eventId로 1행 매칭된다`() {
        val (attacker, victim) = twoEvents()
        val guest = mkGuest(victim)
        val attackerGroupId = mkGroup(attacker, "공격자 그룹")
        val victimGroupId = mkGroup(victim, "피해자 그룹")

        guestMapper.updateSeatGroupId(attacker, listOf(guest.id), attackerGroupId)
        assertNull(queryString("SELECT seat_group_id FROM guest WHERE id = ?", guest.id), "공격 호출 후 seat_group_id는 여전히 null이어야 한다")

        guestMapper.updateSeatGroupId(victim, listOf(guest.id), victimGroupId)
        assertEquals(victimGroupId, queryString("SELECT seat_group_id FROM guest WHERE id = ?", guest.id), "소유 행사 호출은 seat_group_id를 갱신해야 한다")
    }

    // ── #4 ParkingRecordMapper.linkGuest (2 포트 공유 매퍼 — 통제된 동결 해제) ──

    @Test
    fun `linkGuest는 위조 eventId로 0행, 소유 eventId로 1행 매칭된다`() {
        val (attacker, victim) = twoEvents()
        val zoneId = mkZone(victim)
        val record = mkRecord(victim, zoneId, "지하 1층·테스트구역·1", "11가1111")
        val guest = mkGuest(victim, "매핑 대상 참석자")

        parkingRecordMapper.linkGuest(attacker, record.id, guest.id)
        assertNull(queryString("SELECT guest_id FROM parking_record WHERE id = ?", record.id), "공격 호출 후 guest_id는 여전히 null이어야 한다")

        parkingRecordMapper.linkGuest(victim, record.id, guest.id)
        assertEquals(guest.id, queryString("SELECT guest_id FROM parking_record WHERE id = ?", record.id), "소유 행사 호출은 guest_id를 백필해야 한다")
    }

    // ── #5 ParkingRecordMapper.updateSlotMove (record.eventId 프로퍼티 바인딩 — §4) ──

    @Test
    fun `updateSlotMove는 record eventId가 위조면 0행, 소유 행사면 1행 매칭된다`() {
        val (attacker, victim) = twoEvents()
        val zoneId = mkZone(victim)
        val base = mkRecord(victim, zoneId, "지하 1층·테스트구역·2", "22가2222")

        val forged = ParkingRecord(
            id = base.id, eventId = attacker, zoneId = base.zoneId, slotSig = "공격-이동자리",
            plate = base.plate, phone = base.phone, vipName = base.vipName, guestId = base.guestId,
            registeredBy = base.registeredBy, registeredAt = base.registeredAt, status = base.status, reviewNeeded = true,
        )
        parkingRecordMapper.updateSlotMove(forged)
        assertEquals(base.slotSig, queryString("SELECT slot_sig FROM parking_record WHERE id = ?", base.id), "공격(위조 eventId 바인딩) 후 slot_sig가 원본이어야 한다")

        val normal = ParkingRecord(
            id = base.id, eventId = victim, zoneId = base.zoneId, slotSig = "정상-이동자리",
            plate = base.plate, phone = base.phone, vipName = base.vipName, guestId = base.guestId,
            registeredBy = base.registeredBy, registeredAt = base.registeredAt, status = base.status, reviewNeeded = true,
        )
        parkingRecordMapper.updateSlotMove(normal)
        assertEquals("정상-이동자리", queryString("SELECT slot_sig FROM parking_record WHERE id = ?", base.id), "소유 행사 eventId 바인딩은 slot_sig를 갱신해야 한다")
    }

    // ── #6 ParkingRecordMapper.touchRegisteredAt ─────────────────────

    @Test
    fun `touchRegisteredAt은 위조 eventId로 0행, 소유 eventId로 1행 매칭된다`() {
        val (attacker, victim) = twoEvents()
        val zoneId = mkZone(victim)
        val record = mkRecord(victim, zoneId, "지하 1층·테스트구역·3", "33가3333")
        val original = queryString("SELECT registered_at FROM parking_record WHERE id = ?", record.id)

        parkingRecordMapper.touchRegisteredAt(attacker, record.id)
        assertEquals(original, queryString("SELECT registered_at FROM parking_record WHERE id = ?", record.id), "공격 호출 후 registered_at이 그대로여야 한다")

        // DATETIME 초 단위 정밀도 — 동일 초 내 갱신은 값 차이가 관측되지 않으므로 경계를 넘긴다.
        Thread.sleep(1100)
        parkingRecordMapper.touchRegisteredAt(victim, record.id)
        val touched = queryString("SELECT registered_at FROM parking_record WHERE id = ?", record.id)
        assertNotEquals(original, touched, "소유 행사 호출은 registered_at을 갱신해야 한다")
    }

    // ── #7 ParkingRecordMapper.checkout ──────────────────────────────

    @Test
    fun `checkout은 위조 eventId로 0행, 소유 eventId로 1행 매칭된다`() {
        val (attacker, victim) = twoEvents()
        val zoneId = mkZone(victim)
        val record = mkRecord(victim, zoneId, "지하 1층·테스트구역·4", "44가4444")

        parkingRecordMapper.checkout(attacker, record.id)
        assertEquals(ParkingRecord.STATUS_PARKED, queryString("SELECT status FROM parking_record WHERE id = ?", record.id), "공격 호출 후 상태가 주차중이어야 한다")

        parkingRecordMapper.checkout(victim, record.id)
        assertEquals(ParkingRecord.STATUS_CHECKED_OUT, queryString("SELECT status FROM parking_record WHERE id = ?", record.id), "소유 행사 호출은 출차 전이해야 한다")
    }

    // ── #8 ParkingRecordMapper.clearReview ───────────────────────────

    @Test
    fun `clearReview는 위조 eventId로 0행, 소유 eventId로 1행 매칭된다`() {
        val (attacker, victim) = twoEvents()
        val zoneId = mkZone(victim)
        val record = mkRecord(victim, zoneId, "지하 1층·테스트구역·5", "55가5555")
        // review_needed=1 fixture — 승계(케이스 B/C) 흐름 재현 대신 상태 직접 지정(검증 대상은
        // clearReview SQL 자체이지 승계 로직이 아니다).
        execUpdate("UPDATE parking_record SET review_needed = 1 WHERE id = ?", record.id)

        parkingRecordMapper.clearReview(attacker, record.id)
        assertEquals(1, queryInt("SELECT review_needed FROM parking_record WHERE id = ?", record.id), "공격 호출 후 review_needed가 1로 유지돼야 한다")

        parkingRecordMapper.clearReview(victim, record.id)
        assertEquals(0, queryInt("SELECT review_needed FROM parking_record WHERE id = ?", record.id), "소유 행사 호출은 review_needed를 0으로 해제해야 한다")
    }

    // ── #9 SeatAssignmentMapper.deleteEmptySeats ─────────────────────

    @Test
    fun `deleteEmptySeats는 위조 eventId로 0행, 소유 eventId로 1행 매칭된다`() {
        val (attacker, victim) = twoEvents()
        val groupId = mkGroup(victim)
        val filledGuest = mkGuest(victim, "배정 참석자")
        asSystemAdmin {
            assignmentUseCase.replaceAssignments(
                victim,
                BulkAssignCommand(
                    groupNo = 1,
                    assignments = listOf(AssignmentEntry(ord = 1, guestId = null), AssignmentEntry(ord = 2, guestId = filledGuest.id)),
                ),
            )
        }
        val beforeCount = queryInt("SELECT COUNT(*) FROM seat_assignment WHERE seat_group_id = ? AND guest_id IS NULL", groupId)
        assertEquals(1, beforeCount, "fixture — 빈 좌석 1행이 준비돼야 한다")

        seatAssignmentMapper.deleteEmptySeats(attacker, groupId)
        assertEquals(1, queryInt("SELECT COUNT(*) FROM seat_assignment WHERE seat_group_id = ? AND guest_id IS NULL", groupId), "공격 호출 후 빈 좌석 행이 그대로여야 한다")

        seatAssignmentMapper.deleteEmptySeats(victim, groupId)
        assertEquals(0, queryInt("SELECT COUNT(*) FROM seat_assignment WHERE seat_group_id = ? AND guest_id IS NULL", groupId), "소유 행사 호출은 빈 좌석 행을 삭제해야 한다")
    }

    // ── #10 SeatAssignmentMapper.updateOrdForGroup ───────────────────

    @Test
    fun `updateOrdForGroup은 위조 eventId로 0행, 소유 eventId로 1행 매칭된다`() {
        val (attacker, victim) = twoEvents()
        val groupId = mkGroup(victim)
        val g1 = mkGuest(victim, "배정 참석자1")
        val g2 = mkGuest(victim, "배정 참석자2")
        asSystemAdmin {
            assignmentUseCase.replaceAssignments(
                victim,
                BulkAssignCommand(groupNo = 1, assignments = listOf(AssignmentEntry(ord = 1, guestId = g1.id), AssignmentEntry(ord = 2, guestId = g2.id))),
            )
        }

        seatAssignmentMapper.updateOrdForGroup(attacker, groupId, 777)
        val afterAttack = dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT ord FROM seat_assignment WHERE seat_group_id = ? ORDER BY ord").use { stmt ->
                stmt.setString(1, groupId)
                stmt.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getInt("ord") else null }.toList() }
            }
        }
        assertEquals(listOf(1, 2), afterAttack, "공격 호출 후 ord가 원본(1,2)이어야 한다")

        seatAssignmentMapper.updateOrdForGroup(victim, groupId, 777)
        val afterNormal = dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT ord FROM seat_assignment WHERE seat_group_id = ?").use { stmt ->
                stmt.setString(1, groupId)
                stmt.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getInt("ord") else null }.toList() }
            }
        }
        assertEquals(listOf(777, 777), afterNormal, "소유 행사 호출은 그룹 전체 ord를 777로 갱신해야 한다")
    }

    // ── #11 SeatAssignmentMapper.updateOrd ───────────────────────────

    @Test
    fun `updateOrd는 위조 eventId로 0행, 소유 eventId로 1행 매칭된다`() {
        val (attacker, victim) = twoEvents()
        val groupId = mkGroup(victim)
        val guest = mkGuest(victim, "단건 배정 참석자")
        val assigned = asSystemAdmin {
            assignmentUseCase.replaceAssignments(
                victim,
                BulkAssignCommand(groupNo = 1, assignments = listOf(AssignmentEntry(ord = 1, guestId = guest.id))),
            )
        }
        val assignmentId = assigned.single().id
        // groupId는 fixture 확인용으로만 사용(seat_assignment.seat_group_id는 아래 raw 쿼리에서 미참조).
        assertEquals(1, queryInt("SELECT COUNT(*) FROM seat_assignment WHERE seat_group_id = ?", groupId))

        seatAssignmentMapper.updateOrd(attacker, assignmentId, 55)
        assertEquals(1, queryInt("SELECT ord FROM seat_assignment WHERE id = ?", assignmentId), "공격 호출 후 ord가 원본(1)이어야 한다")

        seatAssignmentMapper.updateOrd(victim, assignmentId, 55)
        assertEquals(55, queryInt("SELECT ord FROM seat_assignment WHERE id = ?", assignmentId), "소유 행사 호출은 ord를 55로 갱신해야 한다")
    }

    // ── #12 ParkingSlotTitleMapper.deleteByZoneId (parking_zone EXISTS 프록시) ──

    @Test
    fun `deleteByZoneId는 위조 eventId로 0행, 소유 eventId로 1행 매칭된다`() {
        val (attacker, victim) = twoEvents()
        val zoneId = mkZone(victim)
        asSystemAdmin {
            zoneUseCase.updateZone(
                victim, zoneId,
                ZoneUpdateCommand(
                    part1 = "지하 1층", part2 = "테스트구역", part3 = null, part4 = null,
                    startNo = 1, slotCount = 5, titleOverrides = mapOf("1" to "VIP석"),
                ),
            )
        }
        assertEquals(1, queryInt("SELECT COUNT(*) FROM parking_slot_title WHERE zone_id = ?", zoneId), "fixture — 타이틀 1행이 준비돼야 한다")

        parkingSlotTitleMapper.deleteByZoneId(attacker, zoneId)
        assertEquals(1, queryInt("SELECT COUNT(*) FROM parking_slot_title WHERE zone_id = ?", zoneId), "공격 호출 후 타이틀 행이 그대로여야 한다(EXISTS 서브쿼리 false)")

        parkingSlotTitleMapper.deleteByZoneId(victim, zoneId)
        assertEquals(0, queryInt("SELECT COUNT(*) FROM parking_slot_title WHERE zone_id = ?", zoneId), "소유 행사 호출은 타이틀 행을 삭제해야 한다(EXISTS true)")
    }
}
