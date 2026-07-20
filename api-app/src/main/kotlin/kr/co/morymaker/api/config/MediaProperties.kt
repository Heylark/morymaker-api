package kr.co.morymaker.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 대기화면 미디어 저장 설정(`morymaker.media.*`) — 저장 루트와 서빙 URL 조립 베이스를 담는다.
 * `morymaker.public.*`(web 프론트 오리진)과는 별개 네임스페이스다 — 미디어 base는 api 오리진 +
 * context-path라 성격이 다르다(무비판 편입은 의미 오염).
 *
 * 생성자가 단일이라 Spring Boot가 constructor binding으로 자동 인식한다 — `@Component`를
 * 함께 붙이면 일반 빈 생성 경로와 충돌해 기동 실패로 이어진다(`PublicProperties.kt` 박제 —
 * `String` 파라미터를 빈으로 찾으려 시도). `ApiApplication`의 `@ConfigurationPropertiesScan`이
 * 빈 등록을 전담한다.
 *
 * 전 필드 기본값 보유 — `@SpringBootTest`가 default 프로파일로만 도는 제약에서, 이 프로퍼티가
 * 하나도 없어도 constructor binding이 성립해야 기존 30개 테스트가 붕괴하지 않는다.
 */
@ConfigurationProperties(prefix = "morymaker.media")
data class MediaProperties(
    /** 미디어 저장 루트(repo 상대 기본값 — 개발자 머신 root 권한·재부팅 소실 문제 회피). */
    val root: String = "./var/media",
    /** 서빙 URL 조립 베이스(api 오리진 + context-path) — 로컬은 api(30100)·web(3000) 오리진이 갈리므로 절대 URL 조립에 사용한다. */
    val baseUrl: String = "http://localhost:30100/api",
)
