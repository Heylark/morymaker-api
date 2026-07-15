package kr.co.morymaker.api.config

import org.apache.catalina.connector.Connector
import org.apache.coyote.ajp.AbstractAjpProtocol
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.servlet.server.ServletWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.InetAddress

/**
 * AJP 커넥터를 추가하는 서블릿 컨테이너 팩토리 빈.
 *
 * 이 빈은 프로파일과 무관하게 항상 등록된다 — 프로파일로 게이팅하면 테스트(AJP 없음)와
 * 프로덕션(AJP 있음)이 서로 다른 ServletWebServerFactory를 타게 되어, 컨테이너 배선
 * (context-path 적용 등)을 검증하는 테스트가 프로덕션 경로를 대표하지 못한다.
 * AJP 커넥터 추가 여부만 `enabled`로 가른다.
 *
 * server.port·server.servlet.context-path 등 server.* 프로퍼티는 Boot의
 * WebServerFactoryCustomizerBeanPostProcessor가 수동 생성한 이 factory에도 적용한다 —
 * 직접 Tomcat을 조립하는 방식으로 바꾸면 그 적용이 사라져 context-path가 조용히 증발한다.
 */
@Configuration
class TomcatConfig(
    private val props: TomcatAjpProperties,
) {
    @Bean
    fun servletContainer(): ServletWebServerFactory {
        val tomcat = TomcatServletWebServerFactory()      // 기본 HTTP 커넥터 유지
        if (props.enabled) {
            tomcat.addAdditionalTomcatConnectors(ajpConnector())   // add — HTTP replace 아님
        }
        return tomcat
    }

    private fun ajpConnector(): Connector =
        Connector(props.protocol).apply {
            port = props.port
            (protocolHandler as AbstractAjpProtocol<*>).apply {
                // Ghostcat(CVE-2020-1938) 방어 불변식 — 설정값으로 노출하지 않는다.
                // 값이 비면 Tomcat이 커넥터 init에서 기동을 거부한다(의도된 fail-fast).
                // secret의 getter는 protected라 Kotlin이 property 문법으로 접근 못 한다 — setSecret 직접 호출.
                secretRequired = true
                setSecret(props.secret)
                // 앞단 프록시와 동일 호스트 전제의 안전 기본값. 다른 토폴로지(컨테이너 분리 등)면
                // 설정으로 넓히되, 그 경우에도 위 secret이 방어선으로 남는다.
                address = InetAddress.getByName(props.address)
            }
        }
}
