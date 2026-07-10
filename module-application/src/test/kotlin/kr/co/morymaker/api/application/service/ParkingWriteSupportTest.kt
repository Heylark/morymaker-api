package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.co.morymaker.api.application.parking.SlotOccupiedException
import kr.co.morymaker.api.application.port.`in`.RegisterParkingCommand
import kr.co.morymaker.api.application.port.`in`.RegisterParkingResult
import kr.co.morymaker.api.application.port.out.GuestLink
import kr.co.morymaker.api.application.port.out.GuestLinkPort
import kr.co.morymaker.api.application.port.out.ParkingRecordPort
import kr.co.morymaker.api.domain.parking.ParkingRecord
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import java.time.Instant
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ParkingWriteSupport] 단위 테스트 — 가드-free 등록 코어(무결성 3-5 승계 5케이스 + 매핑 3-7)를
 * 검증한다. 인증 경로([ParkingRecordService])·공개 경로([PublicParkingService])가 공유하는
 * SSOT이므로, 이 테스트가 두 경로 공통 동작의 단일 검증 지점이다(가드-free 구조 리팩터 전
 * `ParkingRecordServiceTest`에 있던 동일 assertion을 그대로 이전한 것 — 동작 변경 없음).
 *
 * 실 DB active_key UNIQUE 동시성·cross-tenant 격리 종합 TC는 Tester 담당 — 여기서는
 * DuplicateKeyException → SlotOccupiedException 번역 경로만 mock으로 검증한다.
 */
class ParkingWriteSupportTest {

    private val recordPort = mockk<ParkingRecordPort>()
    private val guestLinkPort = mockk<GuestLinkPort>()
    private val support = ParkingWriteSupport(recordPort, guestLinkPort)

    private fun sampleRecord(
        id: String = "r1",
        zoneId: String = "z1",
        slotSig: String = "지하 2층·A구역·3",
        plate: String = "12가3456",
        status: String = ParkingRecord.STATUS_PARKED,
        reviewNeeded: Boolean = false,
        guestId: String? = null,
    ) = ParkingRecord(
        id = id, eventId = "ev1", zoneId = zoneId, slotSig = slotSig, plate = plate, phone = null,
        vipName = null, guestId = guestId, registeredBy = ParkingRecord.REGISTERED_BY_STAFF,
        registeredAt = Instant.now(), status = status, reviewNeeded = reviewNeeded,
    )

    private fun sampleCommand(
        slotSig: String = "지하 2층·A구역·3",
        zoneId: String = "z1",
        plate: String = "12가3456",
        phone: String? = "010-1234-5678",
        registeredBy: String = ParkingRecord.REGISTERED_BY_STAFF,
    ) = RegisterParkingCommand(
        slotSig = slotSig, zoneId = zoneId, plate = plate, phone = phone, vipName = "김민준", registeredBy = registeredBy,
    )

    private fun stubNoMapping() {
        every { guestLinkPort.findGuestByPlateOrPhone("ev1", any(), any()) } returns null
    }

    // ── register — registeredBy 검증 ──────────────────────────────

    @Test
    fun `register는 registeredBy가 셀프·요원이 아니면 IllegalArgumentException을 던진다`() {
        assertFailsWith<IllegalArgumentException> {
            support.register("ev1", sampleCommand(registeredBy = "대행"))
        }
    }

    // ── 케이스 E: 신규 주차(빈 대상, 미주차) ──────────────────────

    @Test
    fun `register는 빈 자리·미주차 차량이면 PARKED로 신규 삽입한다`() {
        every { recordPort.selectActiveBySlot("ev1", any()) } returns null
        every { recordPort.selectActiveByPlate("ev1", "12가3456") } returns null
        val inserted = slot<ParkingRecord>()
        every { recordPort.insert(capture(inserted)) } returns Unit
        stubNoMapping()

        val result = support.register("ev1", sampleCommand())

        assertEquals(RegisterParkingResult.RESULT_PARKED, result.result)
        assertFalse(inserted.captured.reviewNeeded)
        assertNull(result.supersededRecord)
        assertFalse(result.mapping.matched)
        verify(exactly = 1) { recordPort.insert(any()) }
        verify(exactly = 0) { recordPort.checkout(any()) }
        verify(exactly = 0) { recordPort.updateSlotMove(any()) }
    }

    @Test
    fun `register는 신규 삽입 시 active_key UNIQUE 위반을 SlotOccupiedException으로 번역한다`() {
        every { recordPort.selectActiveBySlot("ev1", any()) } returns null
        every { recordPort.selectActiveByPlate("ev1", any()) } returns null
        every { recordPort.insert(any()) } throws DuplicateKeyException("uq_precord_active")

        assertFailsWith<SlotOccupiedException> { support.register("ev1", sampleCommand()) }
    }

    // ── 케이스 A: 본인 재등록(동일 자리) ──────────────────────────

    @Test
    fun `register는 대상 점유자가 본인이면 RE_REGISTERED로 registered_at만 갱신한다`() {
        val active = sampleRecord(id = "r1", plate = "12가3456")
        every { recordPort.selectActiveBySlot("ev1", active.slotSig) } returns active
        every { recordPort.selectActiveByPlate("ev1", "12가3456") } returns active
        every { recordPort.touchRegisteredAt("r1") } returns Unit
        every { recordPort.fetchById("ev1", "r1") } returns active
        stubNoMapping()

        val result = support.register("ev1", sampleCommand(slotSig = active.slotSig, plate = "12가3456"))

        assertEquals(RegisterParkingResult.RESULT_RE_REGISTERED, result.result)
        assertEquals("본인 재등록 — 위치 갱신", result.message)
        verify(exactly = 1) { recordPort.touchRegisteredAt("r1") }
        verify(exactly = 0) { recordPort.insert(any()) }
        verify(exactly = 0) { recordPort.checkout(any()) }
    }

    // ── 케이스 B: 승계(타 차량, 신규) ─────────────────────────────

    @Test
    fun `register는 점유 자리에 타 차량이 없이 등록되면 대상을 출차시키고 review_needed로 신규 삽입한다`() {
        val occupied = sampleRecord(id = "old", plate = "99나9999")
        every { recordPort.selectActiveBySlot("ev1", occupied.slotSig) } returns occupied
        every { recordPort.selectActiveByPlate("ev1", "12가3456") } returns null
        every { recordPort.checkout("old") } returns Unit
        val inserted = slot<ParkingRecord>()
        every { recordPort.insert(capture(inserted)) } returns Unit
        stubNoMapping()

        val result = support.register("ev1", sampleCommand(slotSig = occupied.slotSig, plate = "12가3456"))

        assertEquals(RegisterParkingResult.RESULT_SUPERSEDED, result.result)
        assertTrue(inserted.captured.reviewNeeded)
        assertEquals("old", result.supersededRecord?.id)
        assertEquals(ParkingRecord.STATUS_CHECKED_OUT, result.supersededRecord?.status)
        verify(exactly = 1) { recordPort.checkout("old") }
        verify(exactly = 1) { recordPort.insert(any()) }
    }

    // ── 케이스 C: 승계+이동(타 차량, 내 차 타-자리 활성) ──────────

    @Test
    fun `register는 점유 자리 승계와 동시에 내 차량의 기존 기록을 이동시킨다`() {
        val occupied = sampleRecord(id = "old", slotSig = "지하 2층·A구역·3", plate = "99나9999")
        val myOther = sampleRecord(id = "mine", slotSig = "지하 2층·A구역·9", plate = "12가3456")
        every { recordPort.selectActiveBySlot("ev1", occupied.slotSig) } returns occupied
        every { recordPort.selectActiveByPlate("ev1", "12가3456") } returns myOther
        every { recordPort.checkout("old") } returns Unit
        val moved = slot<ParkingRecord>()
        every { recordPort.updateSlotMove(capture(moved)) } returns Unit
        stubNoMapping()

        val result = support.register("ev1", sampleCommand(slotSig = occupied.slotSig, plate = "12가3456"))

        assertEquals(RegisterParkingResult.RESULT_SUPERSEDED, result.result)
        assertEquals("mine", moved.captured.id)
        assertEquals(occupied.slotSig, moved.captured.slotSig)
        assertTrue(moved.captured.reviewNeeded)
        assertEquals("old", result.supersededRecord?.id)
        verify(exactly = 1) { recordPort.checkout("old") }
        verify(exactly = 1) { recordPort.updateSlotMove(any()) }
        verify(exactly = 0) { recordPort.insert(any()) }
    }

    // ── 케이스 D: 자리 이동(빈 대상, 내 차 타-자리 활성) ──────────

    @Test
    fun `register는 대상이 비어있고 내 차량이 다른 자리에 있으면 이동만 수행한다`() {
        val myOther = sampleRecord(id = "mine", slotSig = "지하 2층·A구역·9", plate = "12가3456")
        every { recordPort.selectActiveBySlot("ev1", "지하 2층·A구역·3") } returns null
        every { recordPort.selectActiveByPlate("ev1", "12가3456") } returns myOther
        val moved = slot<ParkingRecord>()
        every { recordPort.updateSlotMove(capture(moved)) } returns Unit
        stubNoMapping()

        val result = support.register("ev1", sampleCommand(slotSig = "지하 2층·A구역·3", plate = "12가3456"))

        assertEquals(RegisterParkingResult.RESULT_RE_REGISTERED, result.result)
        assertEquals("지하 2층·A구역·3", moved.captured.slotSig)
        assertFalse(moved.captured.reviewNeeded)
        assertNull(result.supersededRecord)
        verify(exactly = 0) { recordPort.checkout(any()) }
        verify(exactly = 0) { recordPort.insert(any()) }
    }

    // ── 매핑(3-7) ──────────────────────────────────────────────────

    @Test
    fun `register는 매핑 성공 시 guest_id를 연결하고 대기 상태를 방문으로 전이한다`() {
        every { recordPort.selectActiveBySlot("ev1", any()) } returns null
        every { recordPort.selectActiveByPlate("ev1", any()) } returns null
        val inserted = slot<ParkingRecord>()
        every { recordPort.insert(capture(inserted)) } returns Unit
        every { guestLinkPort.findGuestByPlateOrPhone("ev1", "12가3456", "010-1234-5678") } returns
            GuestLink(guestId = "g1", guestName = "김민준", guestStatus = "대기")
        every { recordPort.linkGuest(any(), "g1") } returns Unit
        every { guestLinkPort.markVisitedAndBackfillPlate("g1", "12가3456") } returns Unit

        val result = support.register("ev1", sampleCommand())

        assertTrue(result.mapping.matched)
        assertEquals("g1", result.mapping.guestId)
        assertEquals("방문", result.mapping.guestStatus)
        verify(exactly = 1) { recordPort.linkGuest(inserted.captured.id, "g1") }
        verify(exactly = 1) { guestLinkPort.markVisitedAndBackfillPlate("g1", "12가3456") }
    }

    @Test
    fun `register는 매핑 실패 시 아무 것도 갱신하지 않는다`() {
        every { recordPort.selectActiveBySlot("ev1", any()) } returns null
        every { recordPort.selectActiveByPlate("ev1", any()) } returns null
        every { recordPort.insert(any()) } returns Unit
        stubNoMapping()

        val result = support.register("ev1", sampleCommand())

        assertFalse(result.mapping.matched)
        verify(exactly = 0) { recordPort.linkGuest(any(), any()) }
        verify(exactly = 0) { guestLinkPort.markVisitedAndBackfillPlate(any(), any()) }
    }

    // ── 구조적 무인증 보장 ────────────────────────────────────────

    @Test
    fun `ParkingWriteSupport는 EventScopeGuard에 의존하지 않는다(구조적으로 assertAccess 호출 불가)`() {
        val constructorParamTypes = ParkingWriteSupport::class.primaryConstructor
            ?.parameters
            ?.map { it.type.toString() }
            .orEmpty()

        assertFalse(
            constructorParamTypes.any { it.contains("EventScopeGuard") },
            "ParkingWriteSupport 생성자에 EventScopeGuard가 있으면 안 된다 — " +
                "공개 경로 재사용 시 실수로 assertAccess를 호출할 여지가 생긴다: $constructorParamTypes",
        )
    }
}
