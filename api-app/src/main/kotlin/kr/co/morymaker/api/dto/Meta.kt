package kr.co.morymaker.api.dto

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * [ApiResponse] 부가 메타 페이로드 — 목록 페이지네이션(§4-1)·이름검색 3상태(§4-9)의 첫 소비자.
 *
 * 필드 각각 `NON_NULL` — 미사용 엔드포인트는 해당 키 자체가 응답에서 생략된다(예: 검색이 아닌
 * 일반 목록 조회는 `searchState` 키 없음).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Meta(
    val total: Int? = null,
    val searchState: String? = null,
    val page: Int? = null,
    val size: Int? = null,
)
