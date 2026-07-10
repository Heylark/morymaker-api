package kr.co.morymaker.api.application.service

/**
 * 발송 게이트 재검증 실패(§7-4) — canSend=false(누락자 존재)인데 발송을 시도하면 던져진다.
 * api-app의 `GlobalExceptionHandler`가 409(`SMS_SEND_BLOCKED`)로 변환한다.
 */
class SmsSendBlockedException(message: String) : RuntimeException(message)
