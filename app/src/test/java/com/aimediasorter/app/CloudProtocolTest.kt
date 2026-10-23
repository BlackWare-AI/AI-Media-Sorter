package com.aimediasorter.app
import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.preferences.AppSettings
import com.aimediasorter.app.domain.cloud.*
import com.aimediasorter.app.domain.storage.OperationPlanner
import com.aimediasorter.app.data.model.TransferMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Mocked official protocol; these tests make no semantic accuracy claims. */
class CloudProtocolTest {
    private val categories=listOf(CategoryEntity("food","Food","Meals",0,"label",1,destinationSubfolder="Food"),
        CategoryEntity("people","People","Humans",0,"label",1,destinationSubfolder="People"))
    private val options=CloudOptions(model="models/gemini-test")
    private val image=listOf(CloudImage(byteArrayOf(1,2,3)))
    private val decision="""{"primary_subject":"A meal","primary_category":"food","independently_significant_secondary_subjects":[],"contextual_objects":[],"pose":"","scene":"indoor","details":[],"category_decision_reason":"The meal is the main subject.","review_required":false}"""
    private fun fields(vararg changes: Pair<String,JsonElement>) = JsonObject(Json.parseToJsonElement(decision).jsonObject.toMutableMap().apply { putAll(changes) }).toString()
    private fun response(d: String=decision,finish: String="STOP")=buildJsonObject {
        put("candidates",buildJsonArray { add(buildJsonObject {
            put("finishReason",finish)
            putJsonObject("content") { put("parts",buildJsonArray { add(buildJsonObject { put("text",d) }) }) }
        }) })
        putJsonObject("usageMetadata") { put("promptTokenCount",10);put("candidatesTokenCount",7);put("thoughtsTokenCount",3) }
    }.toString()
    private fun failure(kind: CloudErrorKind,block: ()->Unit) {
        try { block();fail("Expected safe rejection") } catch(e: CloudFailure) { assertEquals(kind,e.kind) }
    }
    @Test fun defaultIsGeminiOnlyAndFreeTierDoesNotRequireFakeCostEstimate() {
        assertEquals(AnalysisMode.GEMINI,AppSettings().cloudOptions.mode)
        assertFalse(CloudOptions().ready());assertTrue(options.ready())
    }
    @Test fun structuredRequestContainsImagesAndCategoriesWithoutCategoryPriorityOrCredential() {
        val body=CloudProtocol.request(categories,image,options)
        val root=Json.parseToJsonElement(body).jsonObject
        assertEquals("application/json",root["generationConfig"]!!.jsonObject["responseMimeType"]!!.jsonPrimitive.content)
        assertTrue(body.contains("inlineData"));assertFalse(body.contains("\"priority\""))
        assertFalse(body.contains("apiKey"));assertFalse(body.contains("\"confidence\""))
    }
    @Test fun clearSingleCategoryHasNoFabricatedScore() {
        val r=CloudProtocol.parseResponse(response(),setOf("food","people"))
        assertEquals(listOf("food"),r.decision.categories);assertFalse(r.decision.needsReview)
        assertEquals(10,r.inputTokens);assertEquals(10,r.outputTokens)
    }
    @Test fun allImportantCategoriesAreRetainedAndReviewIsDeterministic() {
        val d=fields("primary_category" to JsonPrimitive("people"),"primary_subject" to JsonPrimitive("A person"),
            "independently_significant_secondary_subjects" to Json.parseToJsonElement("""[{"description":"A meal","category":"food","significance_reason":"Deliberately presented alongside the person."}]"""))
        val r=CloudProtocol.parseResponse(response(d),setOf("food","people")).decision
        assertEquals(listOf("people","food"),r.categories);assertTrue(r.needsReview)
        assertEquals("REQUIRED",r.review_status)
    }
    @Test fun emptyAndExplicitlyAmbiguousResultsRequireReviewWithoutGuessingOther() {
        val d=CloudProtocol.parseResponse(response(fields("primary_category" to JsonPrimitive(""))),setOf("food")).decision
        assertTrue(d.categories.isEmpty());assertTrue(d.needsReview)
        assertTrue(CloudProtocol.parseResponse(response(fields("review_required" to JsonPrimitive(true))),setOf("food")).decision.needsReview)
    }
    @Test fun backgroundDetailsDoNotBecomeApplicationSideCategories() {
        val d=fields("contextual_objects" to Json.parseToJsonElement("""["background building","small snack"]"""))
        assertEquals(listOf("food"),CloudProtocol.parseResponse(response(d),setOf("food","people")).decision.categories)
    }
    @Test fun unsupportedCategoriesAndUnlinkedLegacyCategoryListsFailClosed() {
        for(d in listOf(fields("primary_category" to JsonPrimitive("unknown")),
            fields("categories" to Json.parseToJsonElement("""["food","food"]"""))))
            failure(CloudErrorKind.RESPONSE) { CloudProtocol.parseResponse(response(d),setOf("food","people")) }
    }
    @Test fun missingFieldsExtraConfidenceAndNonStringDescriptionsAreRejected() {
        for(d in listOf(decision.dropLast(1)+""","confidence":0.99}""",
            decision.replace(""""pose":"","""",""),decision.replace(""""A meal"""","8"))) {
            failure(CloudErrorKind.RESPONSE) { CloudProtocol.parseResponse(response(d),setOf("food")) }
        }
    }
    @Test fun malformedTruncatedAndBlockedResponsesFailClosed() {
        failure(CloudErrorKind.RESPONSE) { CloudProtocol.parseResponse("not JSON",setOf("food")) }
        failure(CloudErrorKind.RESPONSE) { CloudProtocol.parseResponse(response(finish="MAX_TOKENS"),setOf("food")) }
        failure(CloudErrorKind.BLOCKED) { CloudProtocol.parseResponse(response(finish="SAFETY"),setOf("food")) }
    }
    @Test fun paginatedCatalogExcludesKnownIncompatibleModels()=runBlocking {
        var calls=0
        val client=GeminiClient(CloudTransport { _,_,body ->
            calls++;assertNull(body)
            if(calls==1) CloudHttpResponse(200,"""{"models":[{"name":"models/gemini-a","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-a-tts","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-text-only","inputModalities":["TEXT"],"supportedGenerationMethods":["generateContent"]}],"nextPageToken":"next"}""")
            else CloudHttpResponse(200,"""{"models":[{"name":"models/gemini-b","supportedGenerationMethods":["generateContent"]}]}""")
        })
        assertEquals(listOf("models/gemini-a","models/gemini-b"),client.models("synthetic"))
    }
    @Test fun malformedCatalogIsNotSuccessfulAuthentication()=runBlocking {
        failure(CloudErrorKind.RESPONSE) { runBlocking { GeminiClient(CloudTransport { _,_,_->CloudHttpResponse(200,"{}") }).models("synthetic") } }
    }
    @Test fun retriesReserveEveryAttemptAndHonorBackoff()=runBlocking {
        var requests=0;var reserved=0;val waits=mutableListOf<Long>()
        val client=GeminiClient(CloudTransport { _,_,_->requests++;if(requests==1) CloudHttpResponse(429,"quota",2) else CloudHttpResponse(200,response()) },{waits+=it})
        client.classify("synthetic",options,categories,image) { reserved++ }
        assertEquals(2,requests);assertEquals(2,reserved);assertEquals(listOf(2000L),waits)
    }
    @Test fun quotaHelpMentioningBillingPreservesQuotaStateAndBoundedBackoff()=runBlocking {
        val quota="""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded; check your plan and billing details."}}"""
        var requests=0;var reserved=0;val waits=mutableListOf<Long>()
        val client=GeminiClient(CloudTransport { _,_,_->requests++;CloudHttpResponse(429,quota,2) },{waits+=it})
        failure(CloudErrorKind.QUOTA) { runBlocking { client.classify("synthetic",options,categories,image) { reserved++ } } }
        assertEquals(2,requests);assertEquals(2,reserved);assertEquals(listOf(2000L),waits)
        failure(CloudErrorKind.QUOTA) { runBlocking { client.models("synthetic") } }
        val server=GeminiClient(CloudTransport { _,_,_->CloudHttpResponse(503,"Service unavailable; see billing help.") })
        failure(CloudErrorKind.SERVER) { runBlocking { server.classify("synthetic",options.copy(retries=0),categories,image) {} } }
    }
    @Test fun localBudgetFailurePreventsAnyUpload()=runBlocking {
        var calls=0
        val client=GeminiClient(CloudTransport { _,_,_->calls++;CloudHttpResponse(200,response()) })
        failure(CloudErrorKind.BUDGET) { runBlocking { client.classify("synthetic",options,categories,image) { throw CloudFailure(CloudErrorKind.BUDGET) } } }
        assertEquals(0,calls)
    }
    @Test fun httpFailuresAreDistinctAndNeverExposeProviderBodyOrKey()=runBlocking {
        for((code,kind) in listOf(400 to CloudErrorKind.REQUEST,401 to CloudErrorKind.AUTH,403 to CloudErrorKind.AUTH,
            404 to CloudErrorKind.MODEL,429 to CloudErrorKind.QUOTA,500 to CloudErrorKind.SERVER,503 to CloudErrorKind.SERVER)) {
            val client=GeminiClient(CloudTransport { _,_,_->CloudHttpResponse(code,"synthetic-secret") })
            try { client.classify("synthetic-secret",options.copy(retries=0),categories,image) {};fail() }
            catch(e: CloudFailure) { assertEquals(kind,e.kind);assertFalse(e.message!!.contains("synthetic-secret")) }
        }
    }
    @Test fun invalidKey400AndBillingHaveSpecificStates()=runBlocking {
        for((code,body,kind) in listOf(Triple(400,"API_KEY_INVALID",CloudErrorKind.AUTH),Triple(403,"billing",CloudErrorKind.BILLING))) {
            failure(kind) { runBlocking { GeminiClient(CloudTransport { _,_,_->CloudHttpResponse(code,body) }).classify("synthetic",options.copy(retries=0),categories,image) {} } }
        }
    }
    @Test fun uncertainNetworkAndTimeoutAreNotAutomaticallyRetried()=runBlocking {
        for(kind in listOf(CloudErrorKind.NETWORK,CloudErrorKind.TIMEOUT)) {
            var calls=0
            val client=GeminiClient(CloudTransport { _,_,_->calls++;throw CloudFailure(kind) })
            failure(kind) { runBlocking { client.classify("synthetic",options,categories,image) {} } }
            assertEquals(1,calls)
        }
    }
    @Test fun cancellationStopsWithoutFallback()=runBlocking {
        var calls=0
        val client=GeminiClient(CloudTransport { _,_,_->calls++;throw CancellationException() })
        try { client.classify("synthetic",options,categories,image) {};fail() } catch(_: CancellationException) {}
        assertEquals(1,calls)
    }
    @Test fun failedRowsRetryButCachedSuccessAndManualCorrectionsAreReusable() {
        assertFalse(CloudApproval.shouldReuse(AnalysisMode.GEMINI,"Quota exceeded",1,false,2))
        assertTrue(CloudApproval.shouldReuse(AnalysisMode.GEMINI,"No calibrated Gemini probability is available.",1,false,2))
        assertFalse(CloudApproval.shouldReuse(AnalysisMode.GEMINI,"No calibrated Gemini probability is available.",1,false,604_800_002))
        assertTrue(CloudApproval.shouldReuse(AnalysisMode.GEMINI,"Manual",1,true,999_999_999))
    }
    @Test fun movePlanRequiresUserDestinationAndCopiesAllOthersFirst() {
        try { OperationPlanner.build(7,setOf("people","food"),TransferMethod.MOVE,null);fail() } catch(_: IllegalArgumentException) {}
        val plan=OperationPlanner.build(7,setOf("people","food"),TransferMethod.MOVE,"people")
        assertEquals(listOf("food","people"),plan.map { it.categoryId })
        assertEquals(listOf(TransferMethod.COPY,TransferMethod.MOVE),plan.map { it.method })
        assertEquals(1,plan.count { it.method==TransferMethod.MOVE })
    }
    @Test fun copyPlanNeverContainsMoveAndDoesNotCollapseMultipleCategories() {
        val plan=OperationPlanner.build(7,setOf("people","food"),TransferMethod.COPY,null)
        assertEquals(2,plan.size);assertTrue(plan.all { it.method==TransferMethod.COPY })
    }
    @Test fun responseStagesHaveSpecificSafeDiagnosticsWithoutChangingValidation() {
        val cases=listOf(
            Triple("not JSON",setOf("food"),AnalysisCode.RESPONSE_ENVELOPE),
            Triple("{}",setOf("food"),AnalysisCode.RESPONSE_ENVELOPE),
            Triple(response(finish="MAX_TOKENS"),setOf("food"),AnalysisCode.RESPONSE_MAX_TOKENS),
            Triple(response(finish="OTHER"),setOf("food"),AnalysisCode.RESPONSE_FINISH),
            Triple(response("not JSON"),setOf("food"),AnalysisCode.RESPONSE_JSON),
            Triple(response("[]"),setOf("food"),AnalysisCode.RESPONSE_SCHEMA),
            Triple(response(fields("review_required" to JsonPrimitive("MAYBE"))),setOf("food"),AnalysisCode.RESPONSE_SCHEMA),
            Triple(response(decision.replace("food","unknown")),setOf("food"),AnalysisCode.RESPONSE_CATEGORIES),
            Triple(response(fields("categories" to Json.parseToJsonElement("""["food","food"]"""))),setOf("food"),AnalysisCode.RESPONSE_SCHEMA),
            Triple(response(finish="SAFETY"),setOf("food"),AnalysisCode.RESPONSE_BLOCKED))
        for((body,allowed,code) in cases) {
            try { CloudProtocol.parseResponse(body,allowed);fail("Must reject invalid response") }
            catch(e: CloudFailure) {
                assertEquals(code,e.diagnostic)
                assertFalse(e.message.orEmpty().contains(body))
            }
        }
    }
    @Test fun requestReservationsAreDistinctFromSentAndHttpResponses()=runBlocking {
        val trace=CloudRequestTrace()
        val client=GeminiClient(CloudTransport { _,_,_->CloudHttpResponse(200,response()) })
        try { client.classify("synthetic",options,categories,image,trace) { throw CloudFailure(CloudErrorKind.BUDGET) };fail() }
        catch(_: CloudFailure) {}
        assertEquals(0,trace.requestsSent);assertEquals(0,trace.httpStatus)
        client.classify("synthetic",options,categories,image,trace) { trace.reserved() }
        assertEquals(1,trace.requestsSent);assertEquals(1,trace.reservations);assertEquals(200,trace.httpStatus)
    }
    @Test fun malformedRequestFailsBeforeReservationOrDispatch()=runBlocking {
        var calls=0;var reservations=0
        val client=GeminiClient(CloudTransport { _,_,_->calls++;CloudHttpResponse(200,response()) })
        try { client.classify("synthetic",options,emptyList(),image) { reservations++ };fail() }
        catch(e: CloudFailure) { assertEquals(AnalysisCode.REQUEST_BUILD,e.diagnostic) }
        assertEquals(0,calls);assertEquals(0,reservations)
    }

}
