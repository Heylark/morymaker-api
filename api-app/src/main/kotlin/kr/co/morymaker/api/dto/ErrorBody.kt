package kr.co.morymaker.api.dto

/**
 * 공통 에러 응답 포맷 — `{ "error": { "code", "message", "field" } }`.
 * 성공 응답 포맷(`ApiResponse<T>` — `{data,meta}`)은 실 엔드포인트가 붙는 시점에 함께 추가한다.
 */
data class ErrorBody(val error: ErrorDetail)

data class ErrorDetail(
    val code: String,
    val message: String,
    val field: String? = null,
)
