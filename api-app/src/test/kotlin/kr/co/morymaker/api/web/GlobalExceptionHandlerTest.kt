package kr.co.morymaker.api.web

import io.mockk.every
import io.mockk.mockk
import jakarta.validation.ConstraintViolationException
import kr.co.morymaker.api.application.parking.SlotOccupiedException
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.BindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import kotlin.test.assertEquals

class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `AccessDeniedException은 403과 ROLE_FORBIDDEN을 반환한다`() {
        val response = handler.handleAccessDenied(AccessDeniedException("거부"))

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals("ROLE_FORBIDDEN", response.body?.error?.code)
    }

    @Test
    fun `MethodArgumentNotValidException은 400과 field를 포함한다`() {
        val bindingResult = mockk<BindingResult>()
        val fieldError = FieldError("event", "name", "필수 항목입니다")
        every { bindingResult.fieldErrors } returns listOf(fieldError)
        val exception = mockk<MethodArgumentNotValidException>()
        every { exception.bindingResult } returns bindingResult

        val response = handler.handleValidation(exception)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("VALIDATION_FAILED", response.body?.error?.code)
        assertEquals("name", response.body?.error?.field)
        assertEquals("필수 항목입니다", response.body?.error?.message)
    }

    @Test
    fun `ConstraintViolationException은 400과 VALIDATION_FAILED를 반환한다`() {
        // relaxed — ConstraintViolationException(Set) 생성자가 메시지 조립을 위해 message 외에도
        // getPropertyPath() 등 여러 getter를 내부적으로 호출한다. 필요한 값만 explicit stub.
        val violation = mockk<jakarta.validation.ConstraintViolation<*>>(relaxed = true)
        every { violation.message } returns "값이 유효하지 않습니다"
        val exception = ConstraintViolationException(setOf(violation))

        val response = handler.handleConstraintViolation(exception)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("VALIDATION_FAILED", response.body?.error?.code)
    }

    @Test
    fun `NoSuchElementException은 404와 NOT_FOUND를 반환한다`() {
        val response = handler.handleNotFound(NoSuchElementException("없음"))

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("NOT_FOUND", response.body?.error?.code)
    }

    @Test
    fun `NoResourceFoundException은 404와 NOT_FOUND를 반환한다`() {
        // Spring 6.1+에서 매핑되지 않은 경로는 정적 리소스 핸들러가 이 예외를 던진다 —
        // catch-all(Exception) 핸들러가 먼저 잡아 500으로 응답하지 않는지가 이 테스트의 핵심.
        val response = handler.handleNoResourceFound(NoResourceFoundException(HttpMethod.GET, "/api/probe"))

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("NOT_FOUND", response.body?.error?.code)
    }

    @Test
    fun `NoHandlerFoundException은 404와 NOT_FOUND를 반환한다`() {
        val response = handler.handleNoHandlerFound(NoHandlerFoundException("GET", "/api/probe", org.springframework.http.HttpHeaders()))

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("NOT_FOUND", response.body?.error?.code)
    }

    @Test
    fun `SlotOccupiedException은 409와 SLOT_OCCUPIED를 반환한다`() {
        val response = handler.handleSlotOccupied(SlotOccupiedException())

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("SLOT_OCCUPIED", response.body?.error?.code)
    }

    @Test
    fun `handleIllegalArgument는 400과 VALIDATION_FAILED를 반환한다`() {
        val response = handler.handleIllegalArgument(IllegalArgumentException("검색어(q)는 필수입니다"))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("VALIDATION_FAILED", response.body?.error?.code)
        assertEquals("검색어(q)는 필수입니다", response.body?.error?.message)
    }

    @Test
    fun `메시지 없는 IllegalArgumentException은 기본 문구로 400을 반환한다`() {
        val response = handler.handleIllegalArgument(IllegalArgumentException())

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("입력값을 확인해 주세요", response.body?.error?.message)
    }

    @Test
    fun `IllegalStateException은 요청 검증 핸들러가 아니라 catch-all로 흘러 500이 된다`() {
        // 기존 단위 방식(핸들러 메서드 직접 호출)은 어느 핸들러가 선택되는지(라우팅) 자체를
        // 증명하지 못한다 — 경량 standalone MockMvc로 실제 예외 타입 기반 디스패치를 검증한다.
        // Spring 컨텍스트·DB 불요.
        val mockMvc = MockMvcBuilders.standaloneSetup(RoutingProbeController())
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

        mockMvc.perform(get("/probe/iae"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))

        mockMvc.perform(get("/probe/ise"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
    }

    /** [handleIllegalArgument]/catch-all 라우팅 선택만 증명하는 최소 스텁 — 두 예외 타입을 각각 던진다. */
    @RestController
    private class RoutingProbeController {
        @GetMapping("/probe/iae")
        fun throwIae(): ResponseEntity<Unit> = throw IllegalArgumentException("검증 실패")

        @GetMapping("/probe/ise")
        fun throwIse(): ResponseEntity<Unit> = throw IllegalStateException("상태 오류")
    }

    @Test
    fun `그 외 예외는 500과 INTERNAL_ERROR를 반환한다`() {
        val response = handler.handleUnexpected(RuntimeException("알 수 없는 오류"))

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertEquals("INTERNAL_ERROR", response.body?.error?.code)
    }
}
