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
    // 요청 검증(@Valid) 시 던져지는 ConstraintViolationException·jakarta.validation-api를
    // GlobalExceptionHandler가 참조하므로 명시 배선(컨트롤러가 없는 이번 범위에서도 컴파일에 필요).
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // 명단 엑셀 병합(§4-5·4-6) 업로드 파싱 — GuestExcelParser(컨트롤러 레이어) 전용.
    // application 레이어에는 배선하지 않는다(POI/multipart 오염 회피 — 02-architect §10).
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    // 주차 자리 QR PNG 생성(§6-4) — QrCodeGenerator(컨트롤러 레이어) 전용.
    // application 레이어에는 배선하지 않는다(POI 선례와 동일한 오염 회피 — 02-architect §7).
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}
