package kr.co.morymaker.api.security

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [EventScopeInterceptor]가 `WebMvcConfig`를 통해 실제 `{eid}` 경로 핸들러 체인에 등록되는지
 * 직접 검증한다.
 *
 * 왜 행위(403) 검증이 아니라 등록 검증인가: cross-event 접근은 서비스 계층의
 * `EventScopeGuard.assertAccess` 호출(Layer2b)이 대신 403을 던져 통과시킨다. 즉 이 인터셉터
 * 등록(Layer2a)이 통째로 사라져도 403 응답 자체는 그대로 나므로, 행위만 확인하는 테스트는
 * 이 결함에 대해 구조적으로 눈이 먼다. 그래서 실 핸들러 매핑에 인터셉터가 실제 경로에 대해
 * 체인으로 붙는지를 직접 본다.
 *
 * 컨텍스트에는 동일 타입 빈이 2개 존재한다(`requestMappingHandlerMapping` 외에 actuator의
 * `controllerEndpointHandlerMapping`) — 우리 컨트롤러가 등록되는 쪽은 이름으로 명시 지정한다.
 */
@SpringBootTest
class EventScopeInterceptorRegistrationTest(
    @Qualifier("requestMappingHandlerMapping") @Autowired private val handlerMapping: RequestMappingHandlerMapping,
) {

    @Test
    fun `events {eid} guests 실 경로 핸들러 체인에 EventScopeInterceptor가 등록된다`() {
        val request = MockHttpServletRequest("GET", "/events/ev-1/guests")

        val chain = handlerMapping.getHandler(request)

        assertNotNull(chain, "핸들러 매핑 자체가 없다 — 경로/매핑 전제가 깨졌다")
        assertTrue(chain.interceptorList.any { it is EventScopeInterceptor })
    }
}
