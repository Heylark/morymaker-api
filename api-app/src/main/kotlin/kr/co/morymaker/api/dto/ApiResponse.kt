package kr.co.morymaker.api.dto

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * 공통 성공 응답 포맷 — `{ "data": ... }` (02-api-spec §0-4).
 *
 * `meta`(페이지네이션 등)는 옵셔널이다 — guest 명단 API(§4-1·§4-9)가 첫 실 소비자다.
 * `meta=null`이면 `@JsonInclude(NON_NULL)`이 직렬화 자체를 생략하므로, meta를 넘기지 않는
 * 기존 `EventController` 호출부의 응답은 `{"data":...}`로 그대로 유지된다(byte-identical,
 * 0 회귀 — Tester 검증 대상).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(val data: T, val meta: Meta? = null)
