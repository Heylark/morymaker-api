package kr.co.morymaker.api.application.port.out

/**
 * 외부 문자 발송 경계(§7-4) — 실 구현은 계약 후 어댑터만 교체한다. 현재는 무-네트워크 스텁
 * (`SmsSenderStubAdapter`, api-app)만 존재한다. 발송 행위 자체와 이력 저장([SmsLogPort])은
 * 관심사가 분리돼 있다 — 이 포트는 "보냈는지 여부"만 반환하고 저장 책임은 갖지 않는다.
 */
interface SmsSenderPort {
    fun send(phone: String, body: String): SmsSendResult
}

/** 확장 seam — 실 발송사 도입 시 오류코드 등 필드 추가로 대응(현재는 status만). */
data class SmsSendResult(val status: String)
