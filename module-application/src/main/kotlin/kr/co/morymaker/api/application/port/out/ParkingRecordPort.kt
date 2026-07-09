package kr.co.morymaker.api.application.port.out

import kr.co.morymaker.api.domain.parking.ParkingRecord

/**
 * 주차 기록 영속 포트-out — module-persistence의 `ParkingRecordPersistenceAdapter`가 구현한다.
 *
 * 기존 `ParkingLinkPort`(guest 방향 최소 SPI)와 별개다 — 이 포트는 parking_record 테이블
 * 소유권 전체(zone·record CRUD)를 대표한다(02-architect §6).
 */
interface ParkingRecordPort {

    /** 대상 자리 활성 기록 조회(P1, v2) — 케이스 판정용 단순 조회. 동시성 방어는 active_key UNIQUE 단독. */
    fun selectActiveBySlot(eventId: String, slotSig: String): ParkingRecord?

    /** 같은 차량번호의 다른-자리 활성 기록 조회(§4-1 케이스 판정용). */
    fun selectActiveByPlate(eventId: String, plate: String): ParkingRecord?

    /** 단건 조회(§6-7·§6-8). 없으면 null. */
    fun fetchById(eventId: String, id: String): ParkingRecord?

    /** 목록(§6-5). zoneId·status·reviewNeeded·plateTail(뒷자리 검색) 필터. */
    fun search(eventId: String, query: RecordSearchQuery): List<ParkingRecord>

    /** 신규 삽입(§4-1 케이스 B·E) — active_key UNIQUE 위반 시 DuplicateKeyException. */
    fun insert(record: ParkingRecord)

    /** 자리 이동(§4-1 케이스 C·D) — slot_sig·zone_id·review_needed·guest_id 갱신. */
    fun updateSlotMove(record: ParkingRecord)

    /** 본인 재등록(§4-1 케이스 A) — 위치 재표시, registered_at만 현재 시각으로 갱신. */
    fun touchRegisteredAt(id: String)

    /** 출차(§6-7) — status→출차(active_key NULL화). */
    fun checkout(id: String)

    /** 승계 확인 배지 해제(§6-8) — review_needed→0. */
    fun clearReview(id: String)

    /** parking→guest 매핑(3-7) 성공 시 guest_id 백필. */
    fun linkGuest(recordId: String, guestId: String)
}

/** [ParkingRecordPort.search] 조건(§6-5). `plateTail`은 뒷자리 4자리 계약(LIKE 접미 매칭). */
data class RecordSearchQuery(
    val zoneId: String? = null,
    val status: String? = null,
    val plateTail: String? = null,
    val reviewNeeded: Boolean? = null,
)
