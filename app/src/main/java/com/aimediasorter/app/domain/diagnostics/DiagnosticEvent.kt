package com.aimediasorter.app.domain.diagnostics

import com.aimediasorter.app.domain.cloud.AnalysisCode
import com.aimediasorter.app.domain.cloud.CloudFailure
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable enum class DiagnosticKind { APPLICATION, USER_ACTION, WORKER, ANALYSIS_BATCH, MEDIA_ANALYSIS, GEMINI_REQUEST, MODEL_CATALOG, MODEL_TEST, SETTINGS, GALLERY, CORRECTION, DATABASE, PERMISSION, OPERATION_PLAN, COPY, MOVE, RECOVERY, EXPORT }
@Serializable enum class DiagnosticStage { START, COMPLETE, CANCELLED, FAILURE, PREFLIGHT, SOURCE_READ, ORIGINAL_ACCESS, PREPROCESS_IMAGE, PREPROCESS_VIDEO, JPEG_ENCODE, REQUEST_BUILD, RESERVATION, REQUEST_NOT_SENT, REQUEST_SENT, TRANSPORT_CONNECT, HTTP_HEADERS, HTTP_FAILURE, RESPONSE_RECEIVED, RESPONSE_VALIDATION, OUTPUT_FINISH, JSON_PARSE, SCHEMA_VALIDATION, CLASSIFICATION_SUCCESS, MANUAL_REVIEW, QUOTA_PENDING, RETRY_WAIT, CACHE_HIT, DATABASE_READ, DATABASE_WRITE, GALLERY_OPEN, THUMBNAIL_LOAD, FULLSCREEN_OPEN, CATEGORY_UPDATE, PERMISSION_CHECK, PREVIEW, APPROVAL, TRANSFER_CHECKPOINT, INTEGRITY_VERIFIED, TIMESTAMP_RESULT, SOURCE_DELETE_PENDING, BATCH_SUMMARY, LEGACY_SUMMARY, CRASH, PROCESS_RECOVERY, QUEUE_OVERFLOW, STORAGE_FAILURE }
@Serializable enum class DiagnosticStatus { STARTED, SUCCEEDED, FAILED, NEEDS_REVIEW, PENDING, CANCELLED, INFO }
@Serializable enum class DiagnosticMetric { TOTAL_FILES, ITEM_INDEX, ITEM_COUNT, CATEGORY_COUNT, FRAME_COUNT, JPEG_BYTES, OUTPUT_TOKEN_LIMIT, BATCH_REQUEST_LIMIT, DAILY_REQUEST_LIMIT, RETRY_LIMIT, MIN_INTERVAL_SECONDS, INPUT_TOKENS, OUTPUT_TOKENS, WIDTH, HEIGHT, REQUESTS_SENT, RESERVATIONS, RETRY_INDEX, WAIT_MS, SUCCESSFUL, NEEDS_REVIEW, FAILED, PENDING, PROCESSED, VERIFIED, TIMESTAMP_PRESERVED, SOURCE_RETAINED, VIDEO, API_LEVEL, PAGE_BYTES, DROPPED_EVENTS, STORAGE_FAILURES, EVENT_COUNT, VALIDATION_LENGTH, VALIDATION_LIMIT, PROCESS_EXIT_REASON, PROCESS_EXIT_STATUS, RSS_KB, EXCEPTION_TYPE, ANALYSIS_PROTOCOL_VERSION }
@Serializable enum class DiagnosticTransferPhase { STAGING, PUBLISHING, DESTINATION_VERIFIED, SOURCE_DELETE_PENDING, COMPLETED, OTHER }
@Serializable enum class DiagnosticValidation { ENVELOPE, ENVELOPE_PARTS, FINISH, JSON_SYNTAX, PAYLOAD_OBJECT, REQUIRED_FIELDS, FIELD_TYPE_OR_LENGTH, CATEGORY_VALUES, CATEGORY_ARRAY, CATEGORY_STRING, CATEGORY_COUNT, CATEGORY_DUPLICATE, CATEGORY_UNSUPPORTED, SUBJECT_ARRAY, SUBJECT_FIELDS, SUBJECT_IMPORTANCE, SUBJECT_DESCRIPTION, SUBJECT_COUNT, MAIN_SUBJECT_REQUIRED, DETAIL_ARRAY, DETAIL_STRING, DETAIL_COUNT, REVIEW_STATUS, POSE, SCENE, REASON, USAGE_METADATA, PRIMARY_SUBJECT, PRIMARY_CATEGORY, SECONDARY_CATEGORY, SECONDARY_SIGNIFICANCE, CONTEXT_ARRAY, CONTEXT_STRING, CONTEXT_COUNT }
@Serializable enum class DiagnosticViolation { STRING_REQUIRED, EMPTY_NOT_ALLOWED, STRING_TOO_LONG, CONTROL_CHARACTER }
@Serializable enum class DiagnosticError { NONE, UNEXPECTED, PERMISSION_DENIED, DATABASE_FAILED, TRANSFER_NOT_VERIFIED, EXPORT_FAILED, PROCESS_INTERRUPTED, QUEUE_FULL, LOG_STORAGE_FAILED, HISTORICAL_DETAILS_UNAVAILABLE }

/** No arbitrary message, URI, category text, filename, exception text or provider body field exists. */
@Serializable data class DiagnosticEvent(
    val timestampMs: Long, val correlationId: String, val parentId: String?=null, val batchId: String?=null,
    val kind: DiagnosticKind, val stage: DiagnosticStage, val status: DiagnosticStatus,
    val durationMs: Long=0, val error: DiagnosticError=DiagnosticError.NONE,
    val analysisCode: String?=null, val httpStatus: Int?=null, val model: String?=null,
    val metrics: Map<DiagnosticMetric,Long> = emptyMap(),val transferPhase: DiagnosticTransferPhase?=null,val validation: DiagnosticValidation?=null,val sessionId: String?=null,val durationKnown: Boolean=true,val violation: DiagnosticViolation?=null
)
object DiagnosticPrivacy {
    private val uuid=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val model=Regex("models/gemini-[A-Za-z0-9._-]{1,120}")
    fun id(value: String?)=value?.takeIf { uuid.matches(it) }
    fun model(value: String?)=value?.takeIf { model.matches(it) && !it.contains("AQ.",true) && !it.contains("AIza",true) }
    /** Fail closed, at ingestion AND export. Untrusted free-text fields are omitted, never regex-masked into logs. */
    fun sanitize(event: DiagnosticEvent): DiagnosticEvent? {
        val id=id(event.correlationId) ?: return null
        return event.copy(timestampMs=event.timestampMs.coerceIn(0,9_999_999_999_999L),correlationId=id,
            parentId=id(event.parentId),batchId=id(event.batchId),sessionId=id(event.sessionId),durationMs=event.durationMs.coerceIn(0,604_800_000L),
            analysisCode=event.analysisCode?.let { AnalysisCode.safely(it).name },
            httpStatus=event.httpStatus?.takeIf { it in 100..599 },model=model(event.model),
            metrics=event.metrics.entries.take(16).associate { it.key to it.value.coerceIn(0,1_000_000_000L) })
    }
    fun encode(event: DiagnosticEvent): String=json.encodeToString(requireNotNull(sanitize(event)))
    val json=Json { encodeDefaults=true;ignoreUnknownKeys=true }
    fun decode(value: String): DiagnosticEvent?=try { sanitize(json.decodeFromString<DiagnosticEvent>(value)) } catch(_: Exception) { null }
}
fun interface DiagnosticSink { fun record(event: DiagnosticEvent) }

class DiagnosticOperation(private val sink: DiagnosticSink, val kind: DiagnosticKind,
    batchId: String?=null,parentId: String?=null,model: String?=null) {
    val id=UUID.randomUUID().toString()
    val batchId=DiagnosticPrivacy.id(batchId) ?: if(kind==DiagnosticKind.ANALYSIS_BATCH) id else null
    private val parentId=DiagnosticPrivacy.id(parentId)
    private val model=DiagnosticPrivacy.model(model)
    private val started=System.nanoTime()
    init { event(DiagnosticStage.START,DiagnosticStatus.STARTED) }
    fun event(stage: DiagnosticStage,status: DiagnosticStatus=DiagnosticStatus.INFO,
        code: AnalysisCode?=null,error: DiagnosticError=DiagnosticError.NONE,http: Int?=null,
        metrics: Map<DiagnosticMetric,Long> = emptyMap(),transferPhase: DiagnosticTransferPhase?=null,validation: DiagnosticValidation?=null,sessionId: String?=null,violation: DiagnosticViolation?=null) {
        // Logging cannot change analysis/transfer success, throw, or invoke an exception formatter.
        try { sink.record(DiagnosticEvent(System.currentTimeMillis(),id,parentId,batchId,kind,stage,status,
            ((System.nanoTime()-started)/1_000_000).coerceAtLeast(0),error,code?.name,http,model,metrics,transferPhase,validation,DiagnosticPrivacy.id(sessionId),violation=violation)) } catch(_: Exception) { }
    }
    fun child(kind: DiagnosticKind)=DiagnosticOperation(sink,kind,batchId,id,model)
    fun failure(error: Throwable) {
        when(error) {
            is CancellationException -> event(DiagnosticStage.CANCELLED,DiagnosticStatus.CANCELLED)
            is CloudFailure -> event(when(error.diagnostic) {
                AnalysisCode.LOCAL_SOURCE_ACCESS,AnalysisCode.LOCAL_SOURCE_CHANGED -> DiagnosticStage.SOURCE_READ
                AnalysisCode.LOCAL_IMAGE_DECODE -> DiagnosticStage.PREPROCESS_IMAGE
                AnalysisCode.LOCAL_JPEG_ENCODE -> DiagnosticStage.JPEG_ENCODE
                AnalysisCode.LOCAL_VIDEO_DECODE -> DiagnosticStage.PREPROCESS_VIDEO
                AnalysisCode.CONFIG_NOT_READY -> DiagnosticStage.PREFLIGHT
                AnalysisCode.REQUEST_BUILD -> DiagnosticStage.REQUEST_BUILD
                AnalysisCode.RESPONSE_ENVELOPE,AnalysisCode.RESPONSE_TOO_LARGE -> DiagnosticStage.RESPONSE_VALIDATION
                AnalysisCode.RESPONSE_MAX_TOKENS,AnalysisCode.RESPONSE_FINISH,AnalysisCode.RESPONSE_BLOCKED -> DiagnosticStage.OUTPUT_FINISH
                AnalysisCode.HTTP_AUTH,AnalysisCode.HTTP_BILLING,AnalysisCode.HTTP_MODEL,AnalysisCode.HTTP_REQUEST,AnalysisCode.HTTP_SERVER,AnalysisCode.HTTP_UNEXPECTED -> DiagnosticStage.HTTP_FAILURE
                AnalysisCode.RESPONSE_JSON -> DiagnosticStage.JSON_PARSE
                AnalysisCode.RESPONSE_SCHEMA,AnalysisCode.RESPONSE_CATEGORIES -> DiagnosticStage.SCHEMA_VALIDATION
                AnalysisCode.LIMIT_REACHED,AnalysisCode.HTTP_QUOTA,AnalysisCode.CONSENT_INVALID,AnalysisCode.ATTEMPT_UNCERTAIN -> DiagnosticStage.QUOTA_PENDING
                else -> DiagnosticStage.FAILURE
            },DiagnosticStatus.FAILED,code=error.diagnostic)
            is SecurityException -> event(DiagnosticStage.FAILURE,DiagnosticStatus.FAILED,error=DiagnosticError.PERMISSION_DENIED)
            else -> event(DiagnosticStage.FAILURE,DiagnosticStatus.FAILED,error=DiagnosticError.UNEXPECTED)
        }
    }
    suspend fun <T> measure(stage: DiagnosticStage,block: suspend ()->T): T {
        event(stage,DiagnosticStatus.STARTED)
        return try { block().also { event(stage,DiagnosticStatus.SUCCEEDED) } }
        catch(error: Throwable) { event(stage,if(error is CancellationException) DiagnosticStatus.CANCELLED else DiagnosticStatus.FAILED, error=if(stage in setOf(DiagnosticStage.DATABASE_READ,DiagnosticStage.DATABASE_WRITE)) DiagnosticError.DATABASE_FAILED else DiagnosticError.NONE);failure(error);throw error }
    }
}
@Serializable data class DiagnosticSnapshot(
    val events: List<DiagnosticEvent> = emptyList(),val summaries: List<DiagnosticEvent> = emptyList(),
    val droppedEvents: Long=0,val storageFailures: Long=0
)

/** Propagates the existing correlation across a cancellable HTTP exchange, with no media or credentials. */
class DiagnosticTraceContext(val operation: DiagnosticOperation): kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key: kotlin.coroutines.CoroutineContext.Key<DiagnosticTraceContext>
}
