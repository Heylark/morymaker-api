package kr.co.morymaker.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import kotlin.test.assertEquals

/**
 * `server.servlet.context-path` 설정이 실제로 적용되는지 실증하는 유일한 테스트 —
 * 이 한 줄이 설정에서 누락돼도 컴파일·다른 테스트 어디에서도 검출되지 않는다.
 *
 * `TestRestTemplate`이 기본 제공하는 [org.springframework.boot.test.web.client.LocalHostUriTemplateHandler]는
 * `server.servlet.context-path`를 rootUri에 자동으로 붙인다 — 그래서 상대경로로 요청을 짜면
 * context-path가 설정돼 있든 없든 항상 같은 응답이 나와 설정 누락을 검출하지 못한다. 외부에서
 * 실제로 들어오는 그대로 절대경로로 찔러야 의미가 있다.
 *
 * context-path 밖 경로(`/actuator/health`)는 Spring Security가 401로 거부하는 게 아니라, 그
 * 경로에 배포된 웹 애플리케이션 컨텍스트 자체가 없어 컨테이너(Tomcat)가 404를 반환한다 —
 * 요청이 Spring Security 필터 체인에 도달하기도 전에 끝난다. 어느 쪽이든 200이 아니라는 사실이
 * 반대편 판별식과 짝을 이뤄 context-path 존재를 증명한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContextPathBootTest(
    @Autowired private val restTemplate: TestRestTemplate,
    @LocalServerPort private val port: Int,
) {

    private fun get(path: String) =
        restTemplate.getForEntity("http://localhost:$port$path", String::class.java)

    @Test
    fun `외부 URL은 api 하위로만 서빙되고 루트 경로에는 서빙되지 않는다`() {
        // context-path가 붙어야만 앱 내부 경로 /actuator/health 에 도달해 permitAll 매처에 걸린다.
        assertEquals(HttpStatus.OK, get("/api/actuator/health").statusCode)
        // context-path가 빠지면 이쪽이 200이 된다 — 이동했다는 사실 자체를 반대편에서 봉인한다.
        assertEquals(HttpStatus.NOT_FOUND, get("/actuator/health").statusCode)
    }
}
