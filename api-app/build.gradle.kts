// api-app/build.gradle.kts
// boot 앱 — 실 도메인 Resource Server. module-persistence 배선으로 DB 연동이 활성화된다
// (module-persistence가 module-application을 api()로 전이하므로 직접 의존은 중복이지만,
//  api-app 소스가 module-application 타입을 직접 참조하므로 명시적으로 유지한다).
dependencies {
    implementation(project(":module-application"))
    implementation(project(":module-persistence"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}
