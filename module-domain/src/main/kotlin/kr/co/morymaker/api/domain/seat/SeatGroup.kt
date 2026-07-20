package kr.co.morymaker.api.domain.seat

/**
 * 좌석 그룹 — `numbering` 토글 하나로 지정석·자유석·테이블을 통합 표현한다(§5).
 *
 * Guest.kt·ParkingZone.kt와 동일 원칙: 일반 class + id 기반 equals/hashCode(data class 아님), DB
 * 컬럼과 1:1 매핑만 보유한다.
 *
 * @param id 그룹 PK(UUID)
 * @param eventId 소속 행사 PK — 격리 기준
 * @param groupNo 행사 내 그룹 번호(표시 순서·식별, 서버 자동 채번)
 * @param label 자유 라벨(필수) — "3번 테이블"·"A열"·"1층 자유석"
 * @param numbering ON=번호 좌석(지정석, ord 1..N 연속) / OFF=그룹 명단(자유석·테이블, ord 무의미)
 * @param sortOrder 목록 정렬 순서(서버 자동 채번)
 */
class SeatGroup(
    val id: String,
    val eventId: String,
    val groupNo: Int,
    val label: String,
    val numbering: Boolean,
    val sortOrder: Int,
) {
    // 수정(§12-3) — 새 객체 반환(Guest.with() 패턴). groupNo·sortOrder는 등록 시 고정, 수정 불가.
    fun with(
        label: String = this.label,
        numbering: Boolean = this.numbering,
    ): SeatGroup = SeatGroup(id, eventId, groupNo, label, numbering, sortOrder)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SeatGroup) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "SeatGroup(id=$id, groupNo=$groupNo, label=$label, numbering=$numbering)"
}
