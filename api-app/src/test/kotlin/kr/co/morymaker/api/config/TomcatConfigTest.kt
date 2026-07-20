package kr.co.morymaker.api.config

import org.apache.coyote.ajp.AbstractAjpProtocol
import org.junit.jupiter.api.Test
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.test.util.ReflectionTestUtils
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TomcatConfigTest {

    @Test
    fun `enabled=false면 HTTP 커넥터만 있고 AJP는 추가되지 않는다`() {
        val factory = TomcatConfig(TomcatAjpProperties(enabled = false)).servletContainer()
            as TomcatServletWebServerFactory

        assertTrue(factory.additionalTomcatConnectors.isEmpty())
    }

    @Test
    fun `enabled=true면 HTTP는 그대로 두고 secretRequired가 강제된 AJP 커넥터를 추가한다`() {
        val props = TomcatAjpProperties(enabled = true, port = 30101, address = "127.0.0.1", secret = "test-secret")

        val factory = TomcatConfig(props).servletContainer() as TomcatServletWebServerFactory
        val connectors = factory.additionalTomcatConnectors

        // add 방식이라 HTTP 커넥터(factory 자체 기본 커넥터)는 살아있고, AJP만 추가로 붙는다.
        assertEquals(1, connectors.size)
        val handler = connectors[0].protocolHandler as AbstractAjpProtocol<*>
        assertTrue(handler.secretRequired)
        // secret의 getter가 protected라 프로덕션 코드처럼 외부에서 못 읽는다 — 검증만 리플렉션으로.
        assertEquals("test-secret", ReflectionTestUtils.getField(handler, "secret"))
        assertEquals(30101, connectors[0].port)
        assertEquals(InetAddress.getByName("127.0.0.1"), handler.address)
    }

    @Test
    fun `address는 하드코딩이 아니라 설정값을 그대로 반영한다`() {
        val props = TomcatAjpProperties(enabled = true, address = "10.0.0.5", secret = "s")

        val factory = TomcatConfig(props).servletContainer() as TomcatServletWebServerFactory
        val handler = factory.additionalTomcatConnectors[0].protocolHandler as AbstractAjpProtocol<*>

        assertEquals(InetAddress.getByName("10.0.0.5"), handler.address)
    }
}
