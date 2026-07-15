package kr.co.morymaker.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * AJP 커넥터 설정(`morymaker.tomcat.ajp.*`) — 앞단 Apache httpd(mod_jk)가 이 서비스로 트래픽을
 * 전달할 때 쓰는 프로토콜 표면. 전 필드가 기본값을 가지므로 이 프로퍼티가 하나도 없어도(로컬·테스트)
 * constructor binding이 성립한다.
 *
 * 생성자가 단일이라 Spring Boot가 constructor binding으로 자동 인식한다 — `@Component`를
 * 함께 붙이면 일반 빈 생성 경로와 충돌해 기동 실패로 이어진다. `ApiApplication`의
 * `@ConfigurationPropertiesScan`이 빈 등록을 전담한다.
 */
@ConfigurationProperties(prefix = "morymaker.tomcat.ajp")
data class TomcatAjpProperties(
    /** AJP 커넥터 추가 여부 — 기본 비활성. 로컬·테스트는 이 값을 몰라도 무영향. */
    val enabled: Boolean = false,
    val protocol: String = "AJP/1.3",
    /** HTTP 30100과 짝을 이루는 AJP 전용 포트. */
    val port: Int = 30101,
    /** 앞단 프록시와 동일 호스트 전제의 안전 기본값 — 다른 배포 토폴로지면 오버라이드. */
    val address: String = "127.0.0.1",
    /** 기본값 없음 — enabled=true인데 비어 있으면 Tomcat이 커넥터 초기화에서 기동을 거부한다(Ghostcat 방어). */
    val secret: String = "",
)
