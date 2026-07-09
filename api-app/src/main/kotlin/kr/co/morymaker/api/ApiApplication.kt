package kr.co.morymaker.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

// ConfigurationPropertiesScan — PublicProperties(morymaker.public.*) 등 constructor-binding
// 전용 설정 클래스를 일반 컴포넌트 스캔과 분리된 경로로 등록한다(@Component 병용 금지 —
// PublicProperties.kt 문서 주석 참조).
@SpringBootApplication(scanBasePackages = ["kr.co.morymaker.api"])
@ConfigurationPropertiesScan("kr.co.morymaker.api")
class ApiApplication

fun main(args: Array<String>) {
    runApplication<ApiApplication>(*args)
}
