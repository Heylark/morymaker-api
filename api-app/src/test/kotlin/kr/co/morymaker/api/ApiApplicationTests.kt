package kr.co.morymaker.api

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class ApiApplicationTests {

    @Test
    fun `Spring 컨텍스트가 정상 로드된다`() {
        // module-persistence 배선(실 DB 연동) + Resource Server 구성까지 포함해 전체 빈이
        // 정상적으로 조립되는지 검증. 로컬 MariaDB(infra/docker-compose.yml) 기동이 선행돼야 한다.
    }
}
