package kr.co.morymaker.api.dto

import kr.co.morymaker.api.application.port.`in`.SmsGateView
import kr.co.morymaker.api.application.port.`in`.SmsLogView
import kr.co.morymaker.api.application.port.`in`.SmsPreviewView
import kr.co.morymaker.api.application.port.`in`.SmsSendItem
import kr.co.morymaker.api.application.port.`in`.SmsSendResultView
import kr.co.morymaker.api.application.port.`in`.SmsTemplateView

/** 문자 도메인 응답 DTO 일체(§7 shape 1:1 — View 병렬 유지, StatsResponse 선례). */
data class SmsTemplateResponse(
    val eventId: String,
    val body: String,
    val variables: List<String>,
    val updatedAt: String?,
)

data class SmsPreviewResponse(val rendered: String)

data class SmsGateResponse(
    val candidates: Int,
    val blocked: List<SmsBlockedGuestResponse>,
    val alreadySent: Int,
    val canSend: Boolean,
    val appliedTemplate: String,
)

data class SmsBlockedGuestResponse(val guestId: String, val name: String, val missing: List<String>)

data class SmsSendResultResponse(val sent: Int, val failed: Int, val results: List<SmsSendItemResponse>)

data class SmsSendItemResponse(val guestId: String, val phone: String?, val status: String, val smsLogId: String)

data class SmsLogResponse(
    val id: String,
    val guestId: String?,
    val nameSnapshot: String?,
    val phone: String?,
    val sentAt: String,
    val status: String,
    val bodySnapshot: String?,
)

fun SmsTemplateView.toResponse(): SmsTemplateResponse =
    SmsTemplateResponse(eventId = eventId, body = body, variables = variables, updatedAt = updatedAt)

fun SmsPreviewView.toResponse(): SmsPreviewResponse = SmsPreviewResponse(rendered = rendered)

fun SmsGateView.toResponse(): SmsGateResponse = SmsGateResponse(
    candidates = candidates,
    blocked = blocked.map { SmsBlockedGuestResponse(guestId = it.guestId, name = it.name, missing = it.missing) },
    alreadySent = alreadySent,
    canSend = canSend,
    appliedTemplate = appliedTemplate,
)

fun SmsSendItem.toResponse(): SmsSendItemResponse =
    SmsSendItemResponse(guestId = guestId, phone = phone, status = status, smsLogId = smsLogId)

fun SmsSendResultView.toResponse(): SmsSendResultResponse =
    SmsSendResultResponse(sent = sent, failed = failed, results = results.map { it.toResponse() })

fun SmsLogView.toResponse(): SmsLogResponse = SmsLogResponse(
    id = id, guestId = guestId, nameSnapshot = nameSnapshot, phone = phone,
    sentAt = sentAt, status = status, bodySnapshot = bodySnapshot,
)
