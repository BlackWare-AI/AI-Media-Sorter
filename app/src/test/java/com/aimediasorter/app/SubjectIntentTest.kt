package com.aimediasorter.app

import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.domain.cloud.*
import com.aimediasorter.app.domain.diagnostics.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Human-written composition scenarios + MOCKED responses. NOT a visual/model accuracy benchmark. */
class SubjectIntentTest {
    private val ids=setOf("people","food","nature","buildings","animals","vehicles","technology","text","other")
    private val categories=ids.map { CategoryEntity(it,it,"Subject scope",0,"label",99,destinationSubfolder=it) }
    private fun secondary(category: String,description: String="Deliberately featured subject")=buildJsonObject {
        put("description",description);put("category",category)
        put("significance_reason","Framing and interaction give this subject independent visual importance.")
    }
    private fun decision(primary: String="people",subject: String="A posed portrait",context: List<String> = emptyList(),
                         secondary: List<JsonObject> = emptyList(),review: Boolean=false)=buildJsonObject {
        put("primary_subject",subject);put("primary_category",primary)
        put("independently_significant_secondary_subjects",JsonArray(secondary))
        put("contextual_objects",JsonArray(context.map(::JsonPrimitive)))
        put("pose","standing");put("scene","visible setting");put("details",JsonArray(emptyList()))
        put("category_decision_reason","The deliberate composition emphasizes the described subject; incidental setting remains context.")
        put("review_required",review)
    }
    private fun envelope(payload: JsonElement)=buildJsonObject {
        put("candidates",buildJsonArray { add(buildJsonObject {
            put("finishReason","STOP");putJsonObject("content") {
                put("parts",buildJsonArray { add(buildJsonObject { put("text",payload.toString()) }) })
            }
        }) })
    }.toString()
    private fun parse(payload: JsonElement)=CloudProtocol.parseResponse(envelope(payload),ids).decision
    private fun changed(payload: JsonObject,key: String,value: JsonElement)=JsonObject(payload.toMutableMap().apply { put(key,value) })
    private fun reject(payload: JsonElement,code: AnalysisCode=AnalysisCode.RESPONSE_SCHEMA): CloudFailure {
        try { parse(payload);error("Expected rejection") }
        catch(failure: CloudFailure) { assertEquals(code,failure.diagnostic);return failure }
    }

    @Test fun variedMockCompositionsPreserveSubjectsWithoutPromotingContext()=runBlocking {
        data class Case(val id: String,val payload: JsonObject,val categories: List<String>,val review: Boolean=false)
        val cases=listOf(
            Case("restaurant-portrait",decision(context=listOf("plates of food on nearby table")),listOf("people")),
            Case("food-closeup",decision("food","Carefully plated meal",listOf("person at table edge")),listOf("food")),
            Case("landscaped-recreation-scene",decision("nature","Pool and landscaped garden",listOf("house behind garden")),listOf("nature")),
            Case("architectural-facade",decision("buildings","Architectural study of a house",listOf("pool and planting in foreground")),listOf("buildings")),
            Case("person-presenting-food",decision(context=listOf("counter"),secondary=listOf(secondary("food","Meal deliberately presented to camera"))),listOf("people","food"),true),
            Case("food-person-equally-featured",decision("food","Presented meal",secondary=listOf(secondary("people","Person intentionally featured with meal"))),listOf("food","people"),true),
            Case("cityscape-with-portrait",decision(secondary=listOf(secondary("buildings","Prominent cityscape deliberately framed with portrait"))),listOf("people","buildings"),true),
            Case("small-subject-important",decision("nature","Expansive mountain landscape",secondary=listOf(secondary("people","Small but deliberately isolated silhouetted climber"))),listOf("nature","people"),true),
            Case("group-event",decision(subject="Group posing at an event",context=listOf("exhibition stands","refreshments")),listOf("people")),
            Case("pet-portrait",decision("animals","Close-up pet portrait",listOf("owner's hand","house")),listOf("animals")),
            Case("vehicle-advertisement",decision("vehicles","Car deliberately framed for display",listOf("pedestrian","building")),listOf("vehicles")),
            Case("product-photo",decision("technology","Laptop product photograph",listOf("coffee","person's hand")),listOf("technology")),
            Case("text-focused",decision("text","Document photographed for reading",listOf("scenic illustration","people in illustration")),listOf("text")),
            Case("distant-context-people",decision("nature","Waterfront landscape",listOf("tiny incidental pedestrians")),listOf("nature")),
            Case("uncertain-single-subject",decision("buildings","Possibly architecture-focused composition",review=true),listOf("buildings"),true),
            Case("unusable-composition",decision("","",review=false),emptyList(),true),
            Case("multiple-subjects-same-category",decision(secondary=listOf(secondary("people","Second independently featured person"))),listOf("people"))
        )
        var requests=0
        for(case in cases) {
            val client=GeminiClient(CloudTransport { _,_,body ->
                requests++;assertNotNull(body)
                CloudHttpResponse(200,envelope(case.payload))
            })
            val result=client.classify("AQ.SyntheticFixtureOnly",CloudOptions(model="models/gemini-test",retries=0),
                categories,listOf(CloudImage(byteArrayOf(1,2,3)))) {} // No real image decoding or network here.
            assertEquals(case.id,case.categories,result.decision.categories)
            assertEquals(case.id,case.review,result.decision.needsReview)
            assertEquals(case.id,case.payload["contextual_objects"]!!.jsonArray.map { it.jsonPrimitive.content },result.decision.contextual_objects)
            assertEquals(case.id,case.payload["primary_category"]!!.jsonPrimitive.content,result.decision.primary_category)
        }
        assertEquals(17,requests)
    }

    @Test fun requiredSchemaLinksCategoriesToSubjectsAndUsesABooleanReviewFlag() {
        val root=Json.parseToJsonElement(CloudProtocol.request(categories,listOf(CloudImage(byteArrayOf(1))),CloudOptions())).jsonObject
        val schema=root["generationConfig"]!!.jsonObject["responseSchema"]!!.jsonObject
        val properties=schema["properties"]!!.jsonObject
        assertEquals(decision().keys,properties.keys)
        assertEquals(properties.keys,schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertFalse(properties.containsKey("categories"));assertFalse(properties.containsKey("confidence"))
        assertEquals("BOOLEAN",properties["review_required"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(ids+"",properties["primary_category"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        val subject=properties["independently_significant_secondary_subjects"]!!.jsonObject["items"]!!.jsonObject
        assertEquals(setOf("description","category","significance_reason"),subject["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(ids,subject["properties"]!!.jsonObject["category"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        val prompt=root["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        for(term in listOf("framing","composition","relative visual importance","photographic","independently","contextual_objects","No category has fixed priority"))
            assertTrue(term,prompt.contains(term))
        assertFalse(prompt.contains("cat_people"));assertFalse(prompt.contains("restaurant-portrait"))
    }

    @Test fun everyRequiredFieldAndSecondarySignificanceMustBePresentAndTyped() {
        for(key in decision().keys) reject(JsonObject(decision().filterKeys { it!=key }))
        for(value in listOf(JsonPrimitive("false"),JsonPrimitive(0),JsonNull)) reject(changed(decision(),"review_required",value))
        reject(changed(decision(),"primary_subject",JsonPrimitive(7)))
        reject(decision(subject=""))
        reject(decision("","Unknown",secondary=listOf(secondary("food"))))
        reject(decision(secondary=listOf(JsonObject(secondary("food").filterKeys { it!="significance_reason" }))))
        reject(decision(secondary=listOf(changed(secondary("food"),"significance_reason",JsonPrimitive("   ")))))
        reject(decision("unknown"),AnalysisCode.RESPONSE_CATEGORIES)
        reject(decision(secondary=listOf(secondary("unknown"))),AnalysisCode.RESPONSE_CATEGORIES)
    }

    @Test fun configuredPriorityValuesCannotInfluenceTheGeminiRequest() {
        val image=listOf(CloudImage(byteArrayOf(1)))
        val options=CloudOptions(model="models/gemini-test")
        assertEquals(CloudProtocol.request(categories,image,options),
            CloudProtocol.request(categories.mapIndexed { index,category -> category.copy(priority=1000-index) }.reversed(),image,options))
    }

    @Test fun boundsAndContextTypesFailClosedWithNoGuessedCategory() {
        reject(decision(subject="x".repeat(241)))
        reject(decision(context=List(13) { "Incidental setting" }))
        reject(decision(secondary=List(10) { secondary("food") }))
        reject(changed(decision(),"contextual_objects",JsonArray(listOf(JsonPrimitive(7)))))
        reject(changed(decision(),"category_decision_reason",JsonPrimitive("x".repeat(801))))
    }

    @Test fun multipleCategoriesCannotBypassReviewAndContextCannotAddCategories() {
        val many=parse(decision(secondary=listOf(secondary("food"),secondary("nature")),review=false))
        assertEquals(listOf("people","food","nature"),many.categories);assertTrue(many.needsReview)
        assertEquals(true,many.review_required);assertEquals("REQUIRED",many.review_status)
        val one=parse(decision(context=ids.toList()))
        assertEquals(listOf("people"),one.categories);assertFalse(one.needsReview)
    }

    @Test fun legacySavedDecisionsStillDecodeButLegacyWireResponsesAreRejected() {
        val old="""{"categories":["food"],"subjects":[{"description":"Meal","importance":"main"}],"pose":"","scene":"","details":[],"reason":"Meal","review_status":"CLEAR"}"""
        val saved=Json.decodeFromString<CloudDecision>(old)
        assertEquals(listOf("food"),saved.categories);assertNull(saved.primary_subject)
        assertFalse(saved.needsReview)
        reject(Json.parseToJsonElement(old))
        assertNotEquals("gemini-independent-subjects-v2;rgb-jpeg512;video-3-distinct-frames;selected-uri-v1",CloudProtocol.VERSION)
        val new=parse(decision(secondary=listOf(secondary("food"))))
        assertEquals(new,Json.decodeFromString<CloudDecision>(Json.encodeToString(new)))
    }

    @Test fun validationDiagnosticsRevealFieldAndCodeButNeverSubjectText() {
        val privateMarker="PRIVATE_SUBJECT_OR_CONTEXT"
        val events=mutableListOf<DiagnosticEvent>()
        val op=DiagnosticOperation(DiagnosticSink { events+=it },DiagnosticKind.MEDIA_ANALYSIS)
        try {
            CloudProtocol.parseResponse(envelope(decision(secondary=listOf(changed(secondary("food"),"significance_reason",
                JsonPrimitive(privateMarker+"x".repeat(240)))))),ids,op)
            fail("Expected bounded schema rejection")
        } catch(error: CloudFailure) { assertEquals(AnalysisCode.RESPONSE_SCHEMA,error.diagnostic) }
        val failed=events.last { it.status==DiagnosticStatus.FAILED }
        assertEquals(DiagnosticValidation.SECONDARY_SIGNIFICANCE,failed.validation)
        assertEquals(DiagnosticViolation.STRING_TOO_LONG,failed.violation)
        assertFalse(events.joinToString { DiagnosticPrivacy.encode(it) }.contains(privateMarker))
    }

    @Test fun keyEchoIsRedactedFromEveryNewDescriptiveField() {
        val secret="AQ.SyntheticEchoTest"
        val payload=decision(subject=secret,context=listOf(secret),secondary=listOf(buildJsonObject {
            put("description",secret);put("category","food");put("significance_reason",secret)
        }))
        val result=parse(changed(payload,"category_decision_reason",JsonPrimitive(secret))).redact(secret)
        assertFalse(Json.encodeToString(result).contains(secret))
        assertEquals(listOf("people","food"),result.categories)
    }
}
