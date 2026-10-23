package com.aimediasorter.app.domain.cloud

import com.aimediasorter.app.data.diagnostics.Diagnostics
import com.aimediasorter.app.domain.diagnostics.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.local.CloudStateStore
import com.aimediasorter.app.data.model.ClassificationScoreMetrics
import com.aimediasorter.app.data.preferences.AppSettings
import com.aimediasorter.app.data.preferences.GeminiKeyVault
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

object CloudApproval {
    fun shouldReuse(mode: AnalysisMode,note: String,analyzedAt: Long,manuallyCorrected: Boolean,
                    now: Long=System.currentTimeMillis()): Boolean {
        if(manuallyCorrected) return true
        return now-analyzedAt in 0..604_800_000L &&
            note.contains("No calibrated Gemini probability is available.")
    }
    fun requiresIndividualReview(engine: String)=engine.startsWith("GEMINI")
}
class CloudAnalysisService(context: Context,
    private val client: GeminiClient=GeminiClient(GeminiHttpTransport()),
    namespace: String="user",databaseName: String="gemini_private_state.db") {
    val vault=GeminiKeyVault(context,namespace)
    val state=CloudStateStore(context,databaseName)
    private val encoder=CloudMediaEncoder(context)
    private fun digest(s: String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    fun authorize(signature: String,options: CloudOptions): String {
        if(!vault.hasKey()) throw CloudFailure(CloudErrorKind.AUTH)
        return state.authorize(signature,vault.revision,options)
    }
    suspend fun refreshModels(parent: DiagnosticOperation?=null): List<String> {
        val operation=Diagnostics.operation(DiagnosticKind.MODEL_CATALOG,parentId=parent?.id ?: kotlinx.coroutines.currentCoroutineContext()[DiagnosticTraceContext]?.operation?.id)
        try {
        val credential=try { vault.snapshot() } catch(_: Exception) { throw CloudFailure(CloudErrorKind.AUTH) }
        val models=client.models(credential.secret,operation)
        if(vault.revision!=credential.revision) throw CloudFailure(CloudErrorKind.CONSENT)
        state.replaceModels(credential.revision,models)
        operation.event(DiagnosticStage.COMPLETE,DiagnosticStatus.SUCCEEDED,metrics=mapOf(DiagnosticMetric.ITEM_COUNT to models.size.toLong()))
        return models
        } catch(error: Exception) { operation.failure(error);throw error }
    }
    /** Explicit button only; sends one synthetic image, never a user photo. Uses persisted quotas. */
    suspend fun testSelectedModel(options: CloudOptions) {
        val operation=Diagnostics.operation(DiagnosticKind.MODEL_TEST,parentId=kotlinx.coroutines.currentCoroutineContext()[DiagnosticTraceContext]?.operation?.id,model=options.model)
        try {
        val credential=try { vault.snapshot() } catch(_: Exception) { throw CloudFailure(CloudErrorKind.AUTH) }
        state.unverifyModel(credential.revision,options.model)
        val catalog=refreshModels(operation)
        if(options.model !in catalog) throw CloudFailure(CloudErrorKind.MODEL)
        val signature="explicit-synthetic-model-test:"+options.model+":"+credential.revision
        val limits=options.copy(maxRequests=1,retries=0)
        val batch=state.authorize(signature,credential.revision,limits)
        val image=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        val bytes=try {
            image.eraseColor(Color.BLUE)
            ByteArrayOutputStream().apply { check(image.compress(Bitmap.CompressFormat.JPEG,85,this)) }.toByteArray()
        } finally { image.recycle() }
        val cat=CategoryEntity("test_scene","Test scene","A synthetic color tile",0,"label",1,destinationSubfolder="Test")
        val result=client.classify(credential.secret,limits,listOf(cat),listOf(CloudImage(bytes)),CloudRequestTrace(operation)) {
            if(vault.revision!=credential.revision) throw CloudFailure(CloudErrorKind.CONSENT)
            kotlinx.coroutines.delay(state.reserve(batch,signature,credential.revision,options.minimumIntervalSeconds*1000L))
        }
        if(vault.revision!=credential.revision) throw CloudFailure(CloudErrorKind.CONSENT)
        state.recordUsage(result)
        state.markModelVerified(credential.revision,options.model)
        operation.event(DiagnosticStage.COMPLETE,DiagnosticStatus.SUCCEEDED)
        } catch(error: Exception) { operation.failure(error);throw error }
    }
    suspend fun analyze(settings: AppSettings,batch: String,signature: String,analysisFingerprint: String?,
        categories: List<CategoryEntity>,uri: Uri,path: String?,isVideo: Boolean,orientation: Int,mirrored: Boolean,
        force: Boolean=false,diagnostic: DiagnosticOperation?=null,liveCheck: suspend ()->Unit): ClassificationScoreMetrics {
        val o=settings.cloudOptions
        var cacheKey=""
        val operation=diagnostic ?: Diagnostics.operation(DiagnosticKind.MEDIA_ANALYSIS,batchId=batch,model=o.model)
        val trace=CloudRequestTrace(operation) { state.recordSent(batch,cacheKey) }
        var reused=false
        try {
            if(analysisFingerprint==null) throw CloudFailure(CloudErrorKind.RESPONSE,AnalysisCode.LOCAL_SOURCE_ACCESS)
            if(!o.ready() || o.model !in state.models(vault.revision) || !state.modelVerified(vault.revision,o.model)) throw CloudFailure(CloudErrorKind.CONFIG)
            cacheKey=digest(analysisFingerprint+";"+vault.revision+";"+o.model+";"+o.maxOutputTokens+";"+orientation+";"+mirrored+";"+isVideo+";"+CloudProtocol.VERSION+";"+CloudProtocol.categories(categories))
            var decision=if(force) null else state.cached(cacheKey)
            reused=decision!=null
            var sampledTimes=""
            if(decision==null) {
                if(batch.isBlank()) throw CloudFailure(CloudErrorKind.CONSENT)
                liveCheck()
                state.checkAvailable(batch,signature,vault.revision)
                state.begin(batch,cacheKey)
                val images=operation.measure(if(isVideo) DiagnosticStage.PREPROCESS_VIDEO else DiagnosticStage.PREPROCESS_IMAGE) {
                    encoder.images(uri,path,isVideo,orientation,mirrored)
                }
                operation.event(DiagnosticStage.JPEG_ENCODE,DiagnosticStatus.SUCCEEDED,metrics=mapOf(
                    DiagnosticMetric.FRAME_COUNT to images.size.toLong(),
                    DiagnosticMetric.JPEG_BYTES to images.sumOf { it.bytes.size.toLong() }))
                sampledTimes=images.mapNotNull { it.timestampMs }.joinToString(",")
                val credential=try { vault.snapshot() } catch(_: Exception) { throw CloudFailure(CloudErrorKind.AUTH) }
                var response=client.classify(credential.secret,o,categories,images,trace) {
                    liveCheck()
                    if(vault.revision!=credential.revision) throw CloudFailure(CloudErrorKind.CONSENT)
                    val wait=state.reserve(batch,signature,credential.revision,o.minimumIntervalSeconds*1000L,cacheKey)
                    trace.reserved()
                    kotlinx.coroutines.delay(wait)
                    liveCheck()
                    if(vault.revision!=credential.revision) throw CloudFailure(CloudErrorKind.CONSENT)
                }
                if(isVideo && images.size<2) response=response.copy(decision=response.decision.copy(review_status="REQUIRED",review_required=true))
                liveCheck()
                if(vault.revision!=credential.revision) throw CloudFailure(CloudErrorKind.CONSENT)
                state.complete(batch,cacheKey,response);decision=response.decision
            }
            val result=decision ?: throw CloudFailure(CloudErrorKind.RESPONSE)
            if(!result.categories.all { id -> categories.any { it.id==id } })
                throw CloudFailure(CloudErrorKind.RESPONSE,AnalysisCode.RESPONSE_CATEGORIES)
            if(reused) operation.event(DiagnosticStage.REQUEST_NOT_SENT,DiagnosticStatus.INFO,code=AnalysisCode.CACHE_HIT)
            val one=categories.firstOrNull { it.id==result.categories.singleOrNull() }
            val note="Gemini: "+result.reason+
                (if(isVideo) " Joint sampled-frame analysis at requested 10/50/90 percent offsets; duplicate/undecodable frames omitted. "+
                    (if(sampledTimes.isEmpty()) "Cached sample result." else "Decoded request offsets (ms): "+sampledTimes+".")+" Not complete-video analysis." else "")+
                " No calibrated Gemini probability is available."
            operation.event(if(reused) DiagnosticStage.CACHE_HIT else if(result.needsReview) DiagnosticStage.MANUAL_REVIEW else DiagnosticStage.CLASSIFICATION_SUCCESS,
                if(result.needsReview) DiagnosticStatus.NEEDS_REVIEW else DiagnosticStatus.SUCCEEDED,
                code=if(reused) AnalysisCode.CACHE_HIT else AnalysisCode.SUCCESS,
                metrics=mapOf(DiagnosticMetric.CATEGORY_COUNT to result.categories.size.toLong()))
            return ClassificationScoreMetrics(one?.id ?: "__review__",one?.name ?: "Needs Review",0f,0f,0f,
                needsManualReview=result.needsReview,primarySubjectNote=note,inferenceEngine="GEMINI",
                proposedCategoryIds=result.categories,analysisJson=Json.encodeToString(result),
                analysisStatus=if(result.needsReview) "NEEDS_REVIEW" else "PROPOSED",analysisModel=o.model,
                diagnosticCode=if(reused) AnalysisCode.CACHE_HIT.name else AnalysisCode.SUCCESS.name,
                apiRequestsSent=trace.requestsSent,requestReservations=trace.reservations,responseHttpStatus=trace.httpStatus)
        } catch(cancelled: CancellationException) {
            operation.failure(cancelled)
            if(trace.requestsSent==0) operation.event(DiagnosticStage.REQUEST_NOT_SENT,DiagnosticStatus.CANCELLED)
            throw cancelled
        }
        catch(error: CloudFailure) {
            operation.failure(error)
            if(trace.requestsSent==0) operation.event(DiagnosticStage.REQUEST_NOT_SENT,DiagnosticStatus.INFO,code=error.diagnostic)
            operation.event(if(error.kind in setOf(CloudErrorKind.BUDGET,CloudErrorKind.CONSENT)) DiagnosticStage.QUOTA_PENDING else DiagnosticStage.FAILURE,
                if(error.kind in setOf(CloudErrorKind.BUDGET,CloudErrorKind.CONSENT)) DiagnosticStatus.PENDING else DiagnosticStatus.FAILED,
                code=error.diagnostic,http=trace.httpStatus.takeIf { it!=0 })
            if(batch.isNotBlank() && cacheKey.isNotBlank()) state.failed(batch,cacheKey,error.kind)
            return failed(error.diagnostic,o.model,trace,if(error.kind in setOf(CloudErrorKind.BUDGET,CloudErrorKind.CONSENT)) "PENDING" else "FAILED")
        } catch(_: Exception) {
            operation.event(DiagnosticStage.FAILURE,DiagnosticStatus.FAILED,code=AnalysisCode.INTERNAL)
            if(trace.requestsSent==0) operation.event(DiagnosticStage.REQUEST_NOT_SENT,DiagnosticStatus.INFO,code=AnalysisCode.INTERNAL)
            if(batch.isNotBlank() && cacheKey.isNotBlank()) state.failed(batch,cacheKey,CloudErrorKind.RESPONSE)
            return failed(AnalysisCode.INTERNAL,o.model,trace)
        }
    }
    private fun failed(code: AnalysisCode,model: String,trace: CloudRequestTrace,status: String="FAILED")=
        ClassificationScoreMetrics("__failed__",if(status=="PENDING") "Pending" else "Analysis Failed",0f,0f,0f,
            needsManualReview=true,primarySubjectNote=code.message,inferenceEngine="GEMINI",analysisStatus=status,analysisModel=model,
            diagnosticCode=code.name,apiRequestsSent=trace.requestsSent,requestReservations=trace.reservations,responseHttpStatus=trace.httpStatus)
}
