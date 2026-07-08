// module-persistence/build.gradle.kts
// JPA 전면 배제 — MyBatis 단독. plugin.jpa·kapt·querydsl-*·data-jpa 모두 미포함.
// 이 모듈은 컴파일만 되고 boot(api-app)에는 아직 배선하지 않는다 — 실 DB 배선은 후속 REQ.
dependencies {
    api(project(":module-application"))

    // MyBatis
    api("org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.3")

    // Flyway
    api("org.flywaydb:flyway-core")
    api("org.flywaydb:flyway-mysql")

    // MariaDB JDBC Driver (런타임 전용)
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.4.1")
}
