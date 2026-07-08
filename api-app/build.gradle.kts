// api-app/build.gradle.kts
// boot 앱 — module-application까지만 소비, module-persistence는 미배선 (DB 독립 기동).
dependencies {
    implementation(project(":module-application"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
