package kr.co.morymaker.api.config

import kr.co.morymaker.api.ApiApplication
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `TomcatConfigTest`는 `TomcatConfig(props).servletContainer()`로 커넥터 팩토리만 만들고
 * Tomcat을 실제로 기동하지 않는다 — secret이 빈 문자열이어도 커넥터 객체는 정상 생성된다.
 * Ghostcat 방어(`ajpprotocol.noSecret`)는 `AbstractAjpProtocol.start()` 안, `super.start()`
 * 호출 이전에서 던져지므로 Tomcat이 실제로 기동돼야만 검출할 수 있다 — 이 파일이 그 유일한 경로다.
 *
 * AJP 커넥터는 `start()` 이전 `init()` 단계에서 먼저 포트를 bind한다 — 즉 이 테스트는 AJP 포트를
 * 실제로 연다. 기본값(30101)은 병렬 세션과 충돌할 수 있어 `port=0`(ephemeral)으로 오버라이드한다.
 * HTTP 쪽도 `server.port=0`으로 열어 공유 30100과 충돌하지 않게 한다.
 *
 * 이 테스트는 실 MariaDB 기동을 전제한다(컨텍스트 초기화 중 Flyway가 연결을 시도한다) —
 * 공유 `morymaker-mariadb`(13306)를 재사용하며 kill하지 않는다.
 */
class AjpFailFastBootTest {

    @Test
    fun `AJP 활성 + secret 미주입이면 기동을 거부한다`() {
        // ⚠️ 설계 인계 원문("메시지 사슬 탐색이 안정적")이 실측으로 반증됐다 — 두 가지를 직접
        // 확인하지 않고서는 알 수 없었다:
        // (1) SpringApplicationBuilder.properties(...)는 "default properties"(최저 우선순위)로
        //     등록돼 application.yml의 `server.port: 30100`을 덮어쓰지 못한다 → 공유 30100 포트
        //     충돌(BindException)이 먼저 남. command-line 인자("--key=value") 형태로 넘겨야 함.
        // (2) command-line 인자로 고쳐도, Spring Boot의 `ConnectorStartFailedException`은 실제
        //     Tomcat `LifecycleException`(그 안의 `secretRequired` IllegalArgumentException)을
        //     `.cause`로 보존하지 않는다 — `ex.cause` 사슬은 2단(ApplicationContextException →
        //     ConnectorStartFailedException)에서 끊긴다. 진짜 원인은 Tomcat이 자신의 로거로
        //     "Caused by: ...secretRequired..."를 콘솔에 ERROR 레벨로 찍을 뿐 예외 객체 밖에
        //     남는다 — 그래서 이 테스트는 System.out을 캡처해 그 로그 라인을 검증한다.
        val captured = ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(PrintStream(captured, true, "UTF-8"))
        try {
            assertFailsWith<Exception> {
                SpringApplicationBuilder(ApiApplication::class.java)
                    .run(
                        "--morymaker.tomcat.ajp.enabled=true",
                        "--morymaker.tomcat.ajp.secret=",
                        "--morymaker.tomcat.ajp.port=0",
                        "--server.port=0",
                    )
                    .close()
            }
        } finally {
            System.setOut(originalOut)
        }
        val output = captured.toString("UTF-8")
        print(output) // 캡처한 로그를 원래 stdout으로도 흘려보내 실패 시 Gradle 리포트에서 보이게 한다.
        assertTrue(
            output.contains("secretRequired") && output.contains("IllegalArgumentException"),
            "AJP 커넥터 기동 실패 로그에 secretRequired 관련 메시지가 없음 — 다른 사유로 실패했을 가능성. " +
                "캡처된 출력 마지막 4000자:\n${output.takeLast(4000)}",
        )
    }
}
