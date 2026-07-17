package kr.co.morymaker.api.config

import jakarta.annotation.PostConstruct
import kr.co.morymaker.api.application.port.out.SmsSenderPort
import kr.co.morymaker.api.security.SmsSenderStubAdapter
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * prod 프로파일 기동 시 SMS 발송 어댑터가 스텁으로 구동되지 않는지 검증하는 fail-fast 가드.
 *
 * [SmsSenderStubAdapter]는 실 네트워크 호출 없이 항상 성공을 반환한다 — 콘솔에는 발송 성공
 * 배지가 뜨지만 실제 문자는 0건 발송되는 조용한 실패다. 이 비대칭이 빌드·테스트·기동 어디에도
 * 걸리지 않던 것을 기동 단계에서 막는다(issuer·url 가드와 같은 취지, 검증 대상만 값이 아닌
 * 활성 빈의 타입이다).
 *
 * 실 채널 어댑터가 추가되면 별도 마커 없이 "스텁 타입이 아님"만으로 자동 통과한다 — 이 가드가
 * 스텁 타입을 아는 유일한 지점이며, 실 어댑터 쪽에는 아무 의무도 부과하지 않는다.
 */
@Component
@Profile("prod")
class SmsSenderProdGuard(
    private val smsSenderPort: SmsSenderPort,
) {

    @PostConstruct
    fun validate() {
        check(smsSenderPort !is SmsSenderStubAdapter) {
            "SMS 발송 어댑터가 prod 프로파일에서 스텁(SmsSenderStubAdapter)으로 구동되고 있습니다 " +
                "— 실 채널 어댑터로 교체 후 배포하세요."
        }
    }
}
