package kr.co.morymaker.api.config

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * prod 프로파일 기동 시 외부 발행 URL 설정을 검증하는 fail-fast 가드.
 *
 * 이 세 값은 QR 이미지·SMS 링크로 외부에 나가는 순간 되돌릴 수 없다 — 주차 표지판은 물리
 * 재인쇄가, 초대 SMS는 회수 자체가 불가능하다. 오설정이 빌드·테스트·기동 어디에도 걸리지 않던
 * 비대칭을 기동 단계에서 막는다(issuer 가드와 같은 취지, 대상만 다르다).
 *
 * 기본값은 전부 http://localhost 이므로 https 검사가 "기본값을 그대로 배포" 사고를 함께 차단한다.
 */
@Component
@Profile("prod")
class ProdUrlConfigGuard {

    @Value("\${morymaker.public.event-base-url:}")
    private lateinit var eventBaseUrl: String

    @Value("\${morymaker.parking.qr-base-url:}")
    private lateinit var qrBaseUrl: String

    @Value("\${morymaker.public.park-scan-url:}")
    private lateinit var parkScanUrl: String

    @PostConstruct
    fun validate() {
        mapOf(
            "morymaker.public.event-base-url" to eventBaseUrl,
            "morymaker.parking.qr-base-url" to qrBaseUrl,
            "morymaker.public.park-scan-url" to parkScanUrl,
        ).forEach { (name, value) -> validateUrl(name, value) }
    }

    private fun validateUrl(name: String, value: String) {
        check(value.isNotBlank()) { "$name 값이 비어 있습니다 — prod 프로파일에서는 필수입니다." }
        check(value.startsWith("https://")) {
            "$name 는 prod 프로파일에서 https:// 로 시작해야 합니다. 현재 값: '$value'"
        }
    }
}
