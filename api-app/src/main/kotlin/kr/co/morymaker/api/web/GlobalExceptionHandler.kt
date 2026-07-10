package kr.co.morymaker.api.web

import com.fasterxml.jackson.databind.exc.MismatchedInputException
import jakarta.validation.ConstraintViolationException
import kr.co.morymaker.api.application.parking.SlotOccupiedException
import kr.co.morymaker.api.application.seat.SeatConflictException
import kr.co.morymaker.api.application.security.EventAccessDeniedException
import kr.co.morymaker.api.application.service.EventNotOpenException
import kr.co.morymaker.api.application.service.SmsSendBlockedException
import kr.co.morymaker.api.dto.ErrorBody
import kr.co.morymaker.api.dto.ErrorDetail
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * 디스패처 이후(컨트롤러·서비스·인터셉터) 레벨에서 던져진 예외를 morymaker 공통 에러 포맷으로 변환한다.
 *
 * 인터셉터·서비스 레이어에서 throw한 접근 거부 예외는 Spring Security의 필터 레벨
 * `AccessDeniedHandler`가 잡지 못한다(인터셉터는 필터 체인 이후, 디스패처 내부에서 실행되기
 * 때문) — 이 클래스가 그 예외들의 유일한 트랩이다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    // EventAccessDeniedException은 AccessDeniedException의 하위 타입이지만, 같은 클래스 안에 두
    // 핸들러가 모두 있으면 Spring이 더 구체적인 타입(이 메서드)을 우선 선택한다(예외 계층 기준
    // 최근접 매칭) — 아래 handleAccessDenied는 그 외(주로 @PreAuthorize 역할 거부)만 받는다.
    @ExceptionHandler(EventAccessDeniedException::class)
    fun handleEventAccessDenied(e: EventAccessDeniedException): ResponseEntity<ErrorBody> =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ErrorBody(ErrorDetail("EVENT_FORBIDDEN", e.message ?: "담당 행사가 아닙니다")))

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(e: AccessDeniedException): ResponseEntity<ErrorBody> =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ErrorBody(ErrorDetail("ROLE_FORBIDDEN", e.message ?: "접근 권한이 없습니다")))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorBody> {
        val fieldError = e.bindingResult.fieldErrors.firstOrNull()
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(
                ErrorBody(
                    ErrorDetail(
                        code = "VALIDATION_FAILED",
                        message = fieldError?.defaultMessage ?: "입력값을 확인해 주세요",
                        field = fieldError?.field,
                    ),
                ),
            )
    }

    // Kotlin data class 요청 바디에 non-null 필드가 JSON에서 통째로 빠지면 @Valid가 도달하기 전
    // 역직렬화 자체가 실패한다(jackson-module-kotlin이 MismatchedInputException 계열을 던짐) —
    // @NotBlank 같은 필드값 검증과 달리 "필드 자체가 없음"이라 걸러지지 않으면 catch-all(Exception)이
    // 500으로 새어나간다(실측 확인 — EventCreateRequest.name 누락 케이스).
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMessageNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorBody> {
        val field = (e.cause as? MismatchedInputException)?.path?.lastOrNull()?.fieldName
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorBody(ErrorDetail("VALIDATION_FAILED", "요청 본문을 확인해 주세요", field)))
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(e: ConstraintViolationException): ResponseEntity<ErrorBody> {
        val violation = e.constraintViolations.firstOrNull()
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorBody(ErrorDetail("VALIDATION_FAILED", violation?.message ?: "입력값을 확인해 주세요")))
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(e: NoSuchElementException): ResponseEntity<ErrorBody> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorBody(ErrorDetail("NOT_FOUND", e.message ?: "리소스를 찾을 수 없습니다")))

    // 동시 등록 최종 방어(P5, §6-6.4) — active_key UNIQUE 위반이 서비스에서 이 도메인 예외로
    // 번역되어 올라온다(ParkingRecordService.register). 자리 점유 확정 케이스만 좁게 매핑.
    @ExceptionHandler(SlotOccupiedException::class)
    fun handleSlotOccupied(e: SlotOccupiedException): ResponseEntity<ErrorBody> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorBody(ErrorDetail("SLOT_OCCUPIED", e.message ?: "이미 사용 중인 자리입니다")))

    // 좌석 배정 충돌(§12-5, SEAT-CONCURRENCY-GUARD) — cross-group 중복배정 사전검사 또는
    // guest_id UNIQUE 경쟁 후착·DELETE 갭 경합이 서비스에서 이 도메인 예외로 번역되어 올라온다.
    @ExceptionHandler(SeatConflictException::class)
    fun handleSeatConflict(e: SeatConflictException): ResponseEntity<ErrorBody> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorBody(ErrorDetail("SEAT_CONFLICT", e.message ?: "좌석 배정이 충돌했습니다")))

    // 현장등록 status 게이트 — 종료된 행사만 이 예외 대상이다(준비·운영중은 정상 진행).
    @ExceptionHandler(EventNotOpenException::class)
    fun handleEventNotOpen(e: EventNotOpenException): ResponseEntity<ErrorBody> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorBody(ErrorDetail("EVENT_CLOSED", e.message ?: "종료된 행사입니다")))

    // 문자 발송 게이트 재검증 실패(§7-4) — canSend=false(누락자 존재)인데 발송을 시도한 경우.
    @ExceptionHandler(SmsSendBlockedException::class)
    fun handleSmsSendBlocked(e: SmsSendBlockedException): ResponseEntity<ErrorBody> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorBody(ErrorDetail("SMS_SEND_BLOCKED", e.message ?: "발송할 수 없는 참석자가 있습니다")))

    // 현장등록 공개 POST rate limit 초과 — PublicRateLimitInterceptor가 던진다.
    @ExceptionHandler(RateLimitExceededException::class)
    fun handleRateLimitExceeded(e: RateLimitExceededException): ResponseEntity<ErrorBody> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(ErrorBody(ErrorDetail("RATE_LIMIT_EXCEEDED", e.message ?: "요청이 너무 많습니다")))

    // @Valid 애노테이션이 표현할 수 없는 요청 형태 검증(예: 두 필드 중 최소 하나 필수 —
    // CheckinRequest의 token/guestId)은 컨트롤러가 직접 던지는 이 예외로 처리한다.
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ErrorBody> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorBody(ErrorDetail("VALIDATION_FAILED", e.message ?: "입력값을 확인해 주세요")))

    // 매핑되지 않은 경로 — Spring 6.1+는 정적 리소스 핸들러가 이 예외를 던진다(과거의 "그냥
    // sendError(404)" 방식 대신). catch-all(Exception)이 먼저 잡아 500으로 응답하지 않도록
    // 명시적으로 404로 매핑해야 한다.
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(e: NoResourceFoundException): ResponseEntity<ErrorBody> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorBody(ErrorDetail("NOT_FOUND", "요청한 경로를 찾을 수 없습니다")))

    @ExceptionHandler(NoHandlerFoundException::class)
    fun handleNoHandlerFound(e: NoHandlerFoundException): ResponseEntity<ErrorBody> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorBody(ErrorDetail("NOT_FOUND", "요청한 경로를 찾을 수 없습니다")))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorBody> {
        log.error("처리되지 않은 예외", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorBody(ErrorDetail("INTERNAL_ERROR", "일시적인 오류가 발생했습니다")))
    }
}
