package kr.co.morymaker.api.dto

/**
 * 공통 성공 응답 포맷 — `{ "data": ... }` (02-api-spec §0-4).
 *
 * `meta`(페이지네이션 등)는 이번 REQ 범위의 엔드포인트 어디에도 필요하지 않아 포함하지 않는다 —
 * 목록에 실제로 페이지네이션이 도입되는 시점에 추가한다(YAGNI).
 */
data class ApiResponse<T>(val data: T)
