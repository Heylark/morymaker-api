package kr.co.morymaker.api.application.port.`in`

/**
 * 좌석 배정 유스케이스 포트-in — api-app의 `SeatAssignmentController`가 호출한다(§12-4~5).
 *
 * [replaceAssignments]가 동시성 가드(M1)·M3 동기화를 캡슐화한다(02-architect §4).
 */
interface SeatAssignmentUseCase {

    /** 조회(§12-4). numbering ON=ord 1..N(빈좌석 포함) / OFF=멤버십만. 대용량 page. */
    fun listAssignments(eventId: String, groupNo: Int, page: Int, size: Int): SeatAssignmentListResult

    /** 일괄 배정 변경(§12-5) — 원자 재정렬 + 동시성 가드(M1) + `guest.seat_group_id` 동기화(M3). */
    fun replaceAssignments(eventId: String, command: BulkAssignCommand): List<SeatSlotView>
}

/** [SeatAssignmentUseCase.replaceAssignments] 입력(§12-5) — 그룹의 배정 세트 전체를 대체한다. */
data class BulkAssignCommand(val groupNo: Int, val assignments: List<AssignmentEntry>)

/** 배정 항목 1건 — `ord`는 numbering OFF 그룹에서 서버가 무시하고 [kr.co.morymaker.api.domain.seat.SeatAssignment.ORD_UNNUMBERED]로 강제한다. */
data class AssignmentEntry(val ord: Int, val guestId: String?)

/** [SeatAssignmentUseCase.listAssignments]/[replaceAssignments] 결과 read model 1건. */
data class SeatSlotView(
    val id: String,
    val groupNo: Int,
    val ord: Int,
    val guestId: String?,
    val guestName: String?,
    /** 빈 좌석(guestId=null)일 때만 true — 그 외에는 null(§12-4 응답 예시, empty 키 생략). */
    val empty: Boolean?,
)

/** [SeatAssignmentUseCase.listAssignments] 결과(§12-4 page meta). */
data class SeatAssignmentListResult(val items: List<SeatSlotView>, val total: Int)
