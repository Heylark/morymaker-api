package kr.co.morymaker.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 공개(비로그인) 표면 설정(`morymaker.public.*`) — QR/딥링크 URL 조립 베이스와 현장등록
 * rate limit 임계값을 담는다. 재배포 없이 튜닝 가능하도록 application.yml에서 주입한다.
 *
 * 생성자가 단일이라 Spring Boot가 constructor binding으로 자동 인식한다 — `@Component`를
 * 함께 붙이면 일반 빈 생성 경로와 충돌해 기동 실패로 이어진다(`String` 파라미터를 빈으로
 * 찾으려 시도). `ApiApplication`의 `@ConfigurationPropertiesScan`이 빈 등록을 전담한다.
 */
@ConfigurationProperties(prefix = "morymaker.public")
data class PublicProperties(
    /** 체크인 QR URL 조립 베이스(`{eventBaseUrl}/u/{token}`) — 방문자 표면 도메인. */
    val eventBaseUrl: String,
    /** 주차 진입 QR 스캔 URL(정적값) — 자리 딥링크 기능(후속 작업)의 실데이터 의존 없음. */
    val parkScanUrl: String,
    val rateLimit: RateLimit = RateLimit(),
) {
    data class RateLimit(
        /** 윈도당 허용 요청 수. 공용 데스크 태블릿(단일 IP 다수 정상 등록) 오탐 완화를 위해 넉넉하게 잡는다. */
        val limit: Int = DEFAULT_LIMIT,
        val windowSeconds: Int = DEFAULT_WINDOW_SECONDS,
    )

    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val DEFAULT_WINDOW_SECONDS = 60
    }
}
