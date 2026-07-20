package kr.co.morymaker.api.security

import kr.co.morymaker.api.application.port.out.SmsSendResult
import kr.co.morymaker.api.application.port.out.SmsSenderPort
import kr.co.morymaker.api.domain.sms.SmsLog
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * [SmsSenderPort] 스텁 구현체 — 외부 SDK·네트워크 0(로그 1줄만 남긴다). 실 발송사는 계약 후
 * 이 어댑터만 교체한다(바이트 한도·URL 단축·자격증명 모두 §7-4 범위 외로 이연). 교체 시 읽을
 * 설정 키 자리는 `application.yml`의 `morymaker.sms.*`에 이미 확정돼 있다(vendor·
 * sender-number·api-key·api-secret).
 *
 * 헥사고날 레이어: api-app(adapter) — `EventScopeGuardAdapter`와 동일하게 비-DB @Component로
 * 배치한다. `internal`: application 계층은 [SmsSenderPort] 인터페이스만 의존한다.
 *
 * prod 프로파일에서 이 클래스가 여전히 활성 빈이면 `SmsSenderProdGuard`가 기동을 차단한다 —
 * 문자 0건 발송인데 콘솔엔 성공 배지가 뜨는 조용한 실패를 막기 위함이다.
 */
@Component
internal class SmsSenderStubAdapter : SmsSenderPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(phone: String, body: String): SmsSendResult {
        log.info("SMS 발송(스텁) phone={} bodyLen={}", maskPhone(phone), body.length)
        return SmsSendResult(status = SmsLog.STATUS_SUCCESS)
    }

    /** 로그에 전화번호 원문 노출 금지 — 뒤 4자리만 남기고 마스킹(PII). */
    private fun maskPhone(phone: String): String = phone.takeLast(4).padStart(phone.length, '*')
}
