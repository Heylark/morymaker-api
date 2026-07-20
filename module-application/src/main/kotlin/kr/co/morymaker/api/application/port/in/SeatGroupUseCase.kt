package kr.co.morymaker.api.application.port.`in`

/**
 * 좌석 그룹 유스케이스 포트-in — api-app의 `SeatGroupController`가 호출한다(§12-1~3).
 *
 * numbering 토글 재해석(M4)은 [updateGroup] 내부에서 처리한다 — `seat_assignment` 재정렬이
 * 그룹 수정과 원자적으로 함께 일어나야 하기 때문이다.
 */
interface SeatGroupUseCase {

    /** 목록(§12-1). assignedCount·slotCount 집계 결합. */
    fun listGroups(eventId: String): List<SeatGroupView>

    /** 등록(§12-2). `label` 필수(DTO `@NotBlank` 검증 통과 전제), `groupNo`·`sortOrder` 서버 자동 채번. */
    fun createGroup(eventId: String, command: SeatGroupCreateCommand): SeatGroupView

    /** 수정(§12-3) — label·numbering 토글. numbering 값이 바뀌면 M4 재해석을 동일 트랜잭션에서 수행한다. */
    fun updateGroup(eventId: String, gid: String, command: SeatGroupUpdateCommand): SeatGroupView

    /** 삭제(§12-3). FK CASCADE로 좌석 배정 동반 삭제, FK SET NULL로 `guest.seat_group_id` 자동 해제. */
    fun deleteGroup(eventId: String, gid: String)
}

/** [SeatGroupUseCase.createGroup] 입력(§12-2). */
data class SeatGroupCreateCommand(val label: String, val numbering: Boolean)

/** [SeatGroupUseCase.updateGroup] 입력(§12-3). */
data class SeatGroupUpdateCommand(val label: String, val numbering: Boolean)

/** [SeatGroupUseCase.listGroups]/[createGroup]/[updateGroup] 결과 read model. */
data class SeatGroupView(
    val id: String,
    val groupNo: Int,
    val label: String,
    val numbering: Boolean,
    val sortOrder: Int,
    val assignedCount: Int,
    /** numbering OFF 그룹은 의미가 없어 null(§12-1 응답 예시 — slotCount 키 자체 생략). */
    val slotCount: Int?,
)
