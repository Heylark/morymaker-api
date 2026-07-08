package kr.co.morymaker.api

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class ApiApplicationTests {

    @Test
    fun `Spring 컨텍스트가 정상 로드된다`() {
        // module-persistence 미배선(DB 독립 기동) 상태에서 컨텍스트 로드 자체가 성공하는지 검증.
    }
}
