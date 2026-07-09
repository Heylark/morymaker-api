package kr.co.morymaker.api.config

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * prod 프로파일 기동 시 issuer 설정을 검증하는 fail-fast 가드.
 *
 * issuer 값이 auth 서버와 어긋나면 발급된 모든 토큰이 조용히 거부되어 API가 전면 다운되는데,
 * 이 실패는 런타임 요청이 들어올 때에야 드러난다. 배포 직전 기동 단계에서 미리 잡아내는 편이
 * 운영 중 전면 장애보다 낫다. dev/local 환경은 적용하지 않는다.
 */
@Component
@Profile("prod")
class ProdIssuerConfigGuard {

    @Value("\${morymaker.auth.issuer:}")
    private lateinit var issuer: String

    @PostConstruct
    fun validate() {
        check(issuer.isNotBlank()) {
            "morymaker.auth.issuer 값이 비어 있습니다 — prod 프로파일에서는 필수입니다."
        }
        check(issuer.startsWith("https://")) {
            "morymaker.auth.issuer 는 prod 프로파일에서 https:// 로 시작해야 합니다. 현재 값: '$issuer'"
        }
    }
}
