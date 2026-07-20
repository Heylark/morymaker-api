package kr.co.morymaker.api.application.port.`in`

import kr.co.morymaker.api.domain.parking.ParkingRecord

/**
 * 주차 기록 유스케이스 포트-in — api-app의 `ParkingRecordController`가 호출한다(§6 기록).
 *
 * [register]가 무결성 3-5 승계 3분기 + 매핑 3-7 코어(02-architect §4)를 캡슐화한다 — 요원
 * (PRK-02)·셀프(§10 후속) 양쪽이 동일 로직을 공유한다.
 */
interface ParkingRecordUseCase {

    /** 목록(§6-5). zoneId·status·plateTail(뒷자리 검색)·reviewNeeded 필터. */
    fun listRecords(eventId: String, query: RecordListQuery): List<ParkingRecord>

    /** 등록(§6-6) — 무결성 3-5 승계 3분기 + 매핑 3-7. */
    fun register(eventId: String, command: RegisterParkingCommand): RegisterParkingResult

    /** 출차(§6-7). 이미 출차 상태면 재변경 없이 멱등 재조회. */
    fun checkout(eventId: String, id: String): ParkingRecord

    /** 승계 확인 배지 해제(§6-8). 상태는 무변, review_needed만 해제. */
    fun clearReview(eventId: String, id: String): ParkingRecord
}

/** [ParkingRecordUseCase.listRecords] 입력(§6-5 쿼리). */
data class RecordListQuery(
    val zoneId: String? = null,
    val status: String? = null,
    val plateTail: String? = null,
    val reviewNeeded: Boolean? = null,
)

/** [ParkingRecordUseCase.register] 입력(§6-6). `registeredBy`는 `셀프`/`요원`만 허용. */
data class RegisterParkingCommand(
    val slotSig: String,
    val zoneId: String,
    val plate: String,
    val phone: String?,
    val vipName: String?,
    val registeredBy: String,
)

/**
 * [ParkingRecordUseCase.register] 결과 — 승계 3분기 판정([result])과 매핑([mapping]) 결과를
 * 함께 반환한다. `supersededRecord`는 승계(SUPERSEDED)일 때만 채워진다.
 */
data class RegisterParkingResult(
    val result: String,
    val record: ParkingRecord,
    val mapping: MappingResult,
    val supersededRecord: SupersededInfo?,
    val message: String?,
) {
    companion object {
        const val RESULT_PARKED = "PARKED"
        const val RESULT_SUPERSEDED = "SUPERSEDED"
        const val RESULT_RE_REGISTERED = "RE_REGISTERED"
    }
}

/** parking→guest 매핑(3-7) 결과 — 매칭 실패(`matched=false`)도 정상 상태다. */
data class MappingResult(
    val matched: Boolean,
    val guestId: String? = null,
    val guestName: String? = null,
    val guestStatus: String? = null,
)

/** 승계로 자동 출차된 기존 기록 요약. */
data class SupersededInfo(val id: String, val status: String, val note: String)
