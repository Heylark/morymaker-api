# api/Dockerfile — Gradle 멀티모듈(:api-app:bootJar)을 담는 2스테이지 이미지.
# 저장소에 Gradle toolchain 선언이 없고 빌드 JDK 경로를 고정하는 gradle.properties 는
# gitignored 라, 베이스 이미지 태그가 빌드 JDK를 고정하는 유일한 수단이다.

# ────────────────────────────────────────────────────────────────────
# stage 1: builder
# ────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17.0.19_10-jdk AS builder

WORKDIR /src

# 빌드 스크립트·wrapper만 먼저 COPY — 소스가 바뀌어도 의존성 다운로드 레이어는 캐시로 남는다.
COPY gradlew ./
COPY gradle/ ./gradle/
COPY settings.gradle.kts build.gradle.kts ./
COPY module-domain/build.gradle.kts ./module-domain/
COPY module-application/build.gradle.kts ./module-application/
COPY module-persistence/build.gradle.kts ./module-persistence/
COPY api-app/build.gradle.kts ./api-app/

# 이 레이어가 캐시하는 것은 의존성 "메타데이터"(POM/module 약 490개)와 Gradle 자신의 플러그인
# 클래스패스(jar 46개)뿐이다. 앱 jar 는 받지 않는다 — `dependencies` 는 리포트 태스크라 그래프만
# 해석하고 아티팩트를 내려받지 않기 때문이다(`--configuration runtimeClasspath` 를 붙여도 같다).
# 실측: 이 단계 직후 캐시의 mariadb·mybatis·spring-webmvc·zxing jar 는 전부 0개이고, 앱 jar
# 140개는 아래 bootJar 단계에서 그때 받아진다. 즉 소스가 바뀌면 앱 jar 는 매번 다시 받는다 —
# 이 레이어가 아끼는 건 메타데이터 왕복분이지 jar 다운로드가 아니다. jar 까지 워밍하려면
# 아티팩트를 실제로 해석하는 커스텀 태스크가 필요하고, 그건 빌드 스크립트 변경이라 이 작업이
# 지키기로 한 "프로덕션 코드·설정 변경 0" 계약 밖이다.
# ⚠️ 프로젝트(:api-app)를 반드시 명시할 것 — `dependencies` 만 쓰면 루트 프로젝트만 실행되는데
#    root 는 subprojects{} 로 자식에만 플러그인을 적용해 자신은 구성이 0건이라, 메타데이터조차
#    한 건도 안 받고 조용히 통과한다.
RUN ./gradlew :api-app:dependencies --no-daemon

# 나머지 소스 COPY (.dockerignore 가 build/·.gradle/·gradle.properties 등을 컨텍스트에서 제외한다)
COPY . .

# :api-app:bootJar 단독 실행 — 이 태스크 그래프엔 test 가 없고(테스트 미실행) plain jar 도
# 생성되지 않아(실측 확인) 산출물은 항상 정확히 1개다. 그래도 "정확히 1개"를 셸로 단언한다 —
# 미래에 누군가 이 스테이지를 `./gradlew build` 로 바꾸면 plain jar 가 섞여 들어와 아래 글롭이
# 조용히 2개를 매칭하게 된다. 그 순간을 시끄럽게 만드는 것이 이 가드의 목적이다.
# 산출물 파일명에 버전 문자열을 하드코딩하지도 않는다 — build.gradle.kts 의 version 이 바뀌면
# 파일명도 함께 바뀌기 때문이다.
RUN ./gradlew :api-app:bootJar --no-daemon \
 && set -eu; \
    mkdir -p /app; \
    n=$(ls -1 api-app/build/libs/*.jar | wc -l); \
    [ "$n" -eq 1 ] || { echo "FATAL: bootJar 산출물이 1개가 아님(n=$n) — plain jar 혼입 의심"; ls -l api-app/build/libs/; exit 1; }; \
    mv api-app/build/libs/*.jar /app/app.jar

# ────────────────────────────────────────────────────────────────────
# stage 2: runtime — full JRE 고정(최소화 금지).
# QR PNG 생성(zxing MatrixToImageWriter)이 java.desktop(AWT)을 요구한다 — jlink 최소화
# JRE·distroless 로 바꾸면 QR 생성만 런타임에 조용히 깨진다(빌드·기동·헬스체크는 전부 통과).
# ────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17.0.19_10-jre AS runtime

RUN groupadd -r morymaker && useradd -r -g morymaker morymaker

WORKDIR /app
COPY --from=builder --chown=morymaker:morymaker /app/app.jar /app/app.jar

# media 업로드 디렉토리를 비루트 소유로 미리 만들어 둔다 — WORKDIR(/app)은 root 소유라
# 컨테이너 안에서 morymaker가 var/media를 새로 만들 권한이 없고, 이미지에 마운트포인트가
# 없는 채로 fresh named volume을 붙이면 그 볼륨도 root:root로 생성돼 쓰기가 원리적으로
# 막힌다(boot·헬스체크는 정상이라 업로드 시점까지 조용히 숨어 있는 실패). 이미지에 미리
# morymaker 소유로 만들어 두면 docker가 fresh named volume에 이 소유권을 그대로 복사한다.
RUN mkdir -p /app/var/media && chown -R morymaker:morymaker /app/var

USER morymaker

EXPOSE 50200
# AJP(50201)는 의도적으로 미노출 — 컨테이너 네트워크 토폴로지가 정해지기 전까지 열지 않는다.
# 절대 host publish 금지: secret 이 유일한 방어선인 채로 노출하면 안 된다(토폴로지 결정은
# 별도 배포 인프라 작업에서 compose 네트워크·httpd 연동과 함께 진행).

# health 는 DB 가용성을 집계에 포함한다(DataSourceHealthIndicator) — 앱 기동은 2초 안팎이어도
# DB 준비가 늦으면 그 사이 DOWN 이 뜬다. --start-period 없이 붙이면 컨테이너가 뜨자마자
# unhealthy 로 낙인찍힌다.
HEALTHCHECK --start-period=30s --interval=10s --timeout=3s --retries=3 \
  CMD curl -f http://localhost:50200/api/actuator/health || exit 1

# exec form — java 가 PID 1 로 SIGTERM 을 직접 받는다(shell form 이면 sh 가 PID 1 이 되어
# graceful shutdown 이 깨진다). 프로필·시크릿은 이미지에 굽지 않는다 — 전부 런타임 env 로만
# 주입한다. -D 로 프로필을 구우면 SPRING_PROFILES_ACTIVE 오버라이드가 불가능해지고,
# ENTRYPOINT 에 시크릿을 평문 하드코딩하면 이미지 레이어에 영구 박제되고 같은 PID 네임스페이스의
# 비권한 프로세스가 /proc/*/cmdline 으로 읽어갈 수 있다.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
