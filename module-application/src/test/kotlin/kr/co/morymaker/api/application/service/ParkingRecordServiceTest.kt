package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.co.morymaker.api.application.port.`in`.MappingResult
import kr.co.morymaker.api.application.port.`in`.RecordListQuery
import kr.co.morymaker.api.application.port.`in`.RegisterParkingCommand
import kr.co.morymaker.api.application.port.`in`.RegisterParkingResult
import kr.co.morymaker.api.application.port.out.ParkingRecordPort
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.parking.ParkingRecord
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * [ParkingRecordService] 단위 테스트 — [ParkingRecordPort]/[EventScopeGuard]를 mock으로
 * 대체해 목록·출차·승계 확인 해제 + register() 위임 가드를 검증한다.
 *
 * 등록 코어(무결성 3-5 승계 5케이스 + 매핑 3-7) 자체 검증은 가드-free 추출 이후
 * [ParkingWriteSupportTest]로 이전됐다 — 공개 셀프 주차 경로와 공유하는 SSOT라 그쪽이 두 경로
 * 공통 동작의 단일 검증 지점이다(동작 변경 없음, 검증 자산 이전).
 */
class ParkingRecordServiceTest {

    private val recordPort = mockk<ParkingRecordPort>()
    private val eventScopeGuard = mockk<EventScopeGuard>()
    private val parkingWriteSupport = mockk<ParkingWriteSupport>()
    private val service = ParkingRecordService(recordPort, eventScopeGuard, parkingWriteSupport)

    private fun sampleRecord(
        id: String = "r1",
        zoneId: String = "z1",
        slotSig: String = "지하 2층·A구역·3",
        plate: String = "12가3456",
        status: String = ParkingRecord.STATUS_PARKED,
        reviewNeeded: Boolean = false,
    ) = ParkingRecord(
        id = id, eventId = "ev1", zoneId = zoneId, slotSig = slotSig, plate = plate, phone = null,
        vipName = null, guestId = null, registeredBy = ParkingRecord.REGISTERED_BY_STAFF,
        registeredAt = Instant.now(), status = status, reviewNeeded = reviewNeeded,
    )

    // ── register — 위임 가드 ───────────────────────────────────────

    @Test
    fun `register는 assertAccess 호출 후 ParkingWriteSupport에 위임하고 결과를 그대로 반환한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val command = RegisterParkingCommand(
            slotSig = "지하 2층·A구역·3", zoneId = "z1", plate = "12가3456", phone = null, vipName = null,
            registeredBy = ParkingRecord.REGISTERED_BY_STAFF,
        )
        val expected = RegisterParkingResult(
            result = RegisterParkingResult.RESULT_PARKED,
            record = sampleRecord(),
            mapping = MappingResult(matched = false),
            supersededRecord = null,
            message = null,
        )
        every { parkingWriteSupport.register("ev1", command) } returns expected

        val result = service.register("ev1", command)

        assertEquals(expected, result)
        verify(exactly = 1) { eventScopeGuard.assertAccess("ev1") }
        verify(exactly = 1) { parkingWriteSupport.register("ev1", command) }
    }

    // ── checkout ───────────────────────────────────────────────────

    @Test
    fun `checkout은 주차중 기록을 출차 상태로 전환한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { recordPort.fetchById("ev1", "r1") } returns sampleRecord(id = "r1")
        every { recordPort.checkout("r1") } returns Unit

        val result = service.checkout("ev1", "r1")

        assertEquals(ParkingRecord.STATUS_CHECKED_OUT, result.status)
        verify(exactly = 1) { recordPort.checkout("r1") }
    }

    @Test
    fun `checkout은 이미 출차 상태면 재변경 없이 멱등 재조회한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { recordPort.fetchById("ev1", "r1") } returns sampleRecord(id = "r1", status = ParkingRecord.STATUS_CHECKED_OUT)

        val result = service.checkout("ev1", "r1")

        assertEquals(ParkingRecord.STATUS_CHECKED_OUT, result.status)
        verify(exactly = 0) { recordPort.checkout(any()) }
    }

    @Test
    fun `checkout은 기록이 없으면 NoSuchElementException을 던진다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { recordPort.fetchById("ev1", "ghost") } returns null

        assertFailsWith<NoSuchElementException> { service.checkout("ev1", "ghost") }
    }

    // ── clearReview ────────────────────────────────────────────────

    @Test
    fun `clearReview는 상태 변경 없이 review_needed만 해제한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { recordPort.fetchById("ev1", "r1") } returns sampleRecord(id = "r1", reviewNeeded = true)
        every { recordPort.clearReview("r1") } returns Unit

        val result = service.clearReview("ev1", "r1")

        assertFalse(result.reviewNeeded)
        assertEquals(ParkingRecord.STATUS_PARKED, result.status)
        verify(exactly = 1) { recordPort.clearReview("r1") }
    }

    // ── listRecords ────────────────────────────────────────────────

    @Test
    fun `listRecords는 검색 조건을 그대로 포트에 위임한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { recordPort.search("ev1", any()) } returns listOf(sampleRecord())

        val result = service.listRecords("ev1", RecordListQuery(zoneId = "z1", plateTail = "3456"))

        assertEquals(1, result.size)
        verify(exactly = 1) {
            recordPort.search("ev1", withArg {
                assertEquals("z1", it.zoneId)
                assertEquals("3456", it.plateTail)
            })
        }
    }
}
