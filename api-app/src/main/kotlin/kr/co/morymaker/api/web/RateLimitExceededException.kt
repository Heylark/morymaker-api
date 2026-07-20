package kr.co.morymaker.api.web

/**
 * 공개 현장등록 rate limit 초과 — `PublicRateLimitInterceptor`가 던지고,
 * `GlobalExceptionHandler`가 429(`RATE_LIMIT_EXCEEDED`)로 변환한다.
 */
class RateLimitExceededException(message: String) : RuntimeException(message)
