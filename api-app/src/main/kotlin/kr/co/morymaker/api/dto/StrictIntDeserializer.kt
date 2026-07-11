package kr.co.morymaker.api.dto

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.exc.InvalidFormatException

/**
 * 소수 JSON 입력을 정수로 조용히 절삭하지 않도록 거부하는 필드 한정 디시리얼라이저.
 * Jackson 기본 설정(`ACCEPT_FLOAT_AS_INT`)은 소수 토큰(`1.9`)을 예외 없이 말단
 * 절삭(1.9 → 1)해 통과시키므로, 이 필드에서는 소수 토큰을 명시적으로 거부한다.
 * 정상 정수 토큰은 그대로 통과한다.
 */
class StrictIntDeserializer : JsonDeserializer<Int>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Int {
        if (p.currentToken() == JsonToken.VALUE_NUMBER_FLOAT) {
            throw InvalidFormatException(p, "정수만 허용됩니다(소수 입력은 거부됩니다)", p.text, Int::class.java)
        }
        return p.intValue
    }
}
