package kr.co.morymaker.api.application.port.out

/**
 * 참석자 목록/검색 매퍼 파라미터(DB 언어) — mybatis.md §6 관례(paging 플래그로 LIMIT/OFFSET 제어).
 *
 * `paging=false`로 호출하면 전체 결과를 반환한다(import 병합 시 전체 liveGuests 조회에 사용).
 */
data class GuestSearchQuery(
    val q: String? = null,
    val status: String? = null,
    val src: String? = null,
    val includeCancelled: Boolean = false,
    val pageNo: Int = 1,
    val pageSize: Int = 50,
    val paging: Boolean = true,
) {
    val offset: Int get() = (pageNo - 1) * pageSize
}
