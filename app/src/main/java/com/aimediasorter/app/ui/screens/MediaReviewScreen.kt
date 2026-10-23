package com.aimediasorter.app.ui.screens

import com.aimediasorter.app.data.diagnostics.Diagnostics
import com.aimediasorter.app.domain.diagnostics.*
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.aimediasorter.app.R
import com.aimediasorter.app.data.local.*
import com.aimediasorter.app.domain.cloud.CloudDecision
import com.aimediasorter.app.ui.theme.*
import kotlinx.serialization.json.Json
import java.text.DateFormat
import java.util.Date

@Composable fun MediaReviewScreen(item: MediaItemEntity?,categories: List<CategoryEntity>,links: List<MediaCategoryLink>,
    onSave: (Set<Long>,Set<String>,Boolean)->Unit,onReanalyze: (Set<Long>)->Unit,onOrganize: (Set<Long>)->Unit,
    onExclude: (Long,Boolean)->Unit,onBack: ()->Unit) {
    if(item==null) { Column { Text(stringResource(R.string.details_unavailable));TextButton(onClick=onBack) { Text(stringResource(R.string.btn_back)) } };return }
    val effective=remember(links,item.id) { links.filter { it.mediaId==item.id&&it.origin=="EFFECTIVE" }.map { it.categoryId }.toSet() }
    val proposed=remember(links,item.id) { links.filter { it.mediaId==item.id&&it.origin=="PROPOSED" }.map { it.categoryId }.toSet() }
    var chosen by remember(item.id,item.decisionVersion,effective) { mutableStateOf(effective) }
    var viewer by remember { mutableStateOf(false) }
    val analysis=remember(item.analysisJson) { runCatching { Json.decodeFromString<CloudDecision>(item.analysisJson) }.getOrNull() }
    Column(Modifier.fillMaxSize().background(AmoledPureBlack).verticalScroll(rememberScrollState()).testTag("media-review-scroll").padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)) {
        TextButton(onClick=onBack) { Text(stringResource(R.string.btn_back)) }
        Text(item.fileName,style=MaterialTheme.typography.titleLarge)
        Text(analysisStatusLabel(item.analysisStatus),color=if(item.needsManualReview) StatusAmber else PrimaryPurpleLight)
        MediaThumbnail(item,Modifier.fillMaxWidth().height(220.dp))
        OutlinedButton(onClick={viewer=true},modifier=Modifier.fillMaxWidth().testTag("view-full-image")) { Text(stringResource(R.string.virtual_view_full)) }
        Text(stringResource(R.string.virtual_only_notice),style=MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.virtual_suggestions)+": "+proposed.mapNotNull { id->categories.firstOrNull { it.id==id } }.joinToString { it.name })
        if(analysis!=null) {
            Text(analysis.category_decision_reason ?: analysis.reason)
            if(analysis.primary_subject!=null) {
                Text(stringResource(R.string.virtual_primary_subject)+": "+analysis.primary_subject,modifier=Modifier.testTag("analysis-primary-subject"))
                analysis.independently_significant_secondary_subjects.forEach { subject ->
                    val category=categories.firstOrNull { it.id==subject.category }
                    Text(stringResource(R.string.virtual_significant_subject)+": "+subject.description+
                        (category?.let { " ("+categoryDisplayName(it.id,it.name)+")" } ?: ""))
                    Text(subject.significance_reason,style=MaterialTheme.typography.bodySmall)
                }
                if(analysis.contextual_objects.isNotEmpty()) Text(stringResource(R.string.virtual_context_only)+": "+
                    analysis.contextual_objects.joinToString(" • "),modifier=Modifier.testTag("analysis-context-only"))
            } else analysis.subjects.forEach { Text("• "+it.description+" ("+it.importance+")") }
            if(analysis.pose.isNotBlank()) Text(stringResource(R.string.virtual_pose)+": "+analysis.pose)
            if(analysis.scene.isNotBlank()) Text(stringResource(R.string.virtual_scene)+": "+analysis.scene)
            if(analysis.details.isNotEmpty()) Text(analysis.details.joinToString(" • "))
        } else if(item.primarySubjectNote.isNotBlank()) Text(item.primarySubjectNote,color=StatusAmber)
        AnalysisDiagnosticDetails(item.diagnosticCode,item.apiRequestsSent,item.requestReservations,item.responseHttpStatus)
        if(item.sha256Checksum==null) Text(stringResource(R.string.analysis_original_unavailable),color=StatusAmber)
        Text(stringResource(R.string.virtual_model)+": "+item.analysisModel)
        Text(stringResource(R.string.virtual_analyzed)+": "+DateFormat.getDateTimeInstance().format(Date(item.analyzedAtMs)))
        Text(stringResource(R.string.cloud_score_unavailable),style=MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.virtual_correct),style=MaterialTheme.typography.titleMedium)
        categories.filter { it.isEnabled || it.id in chosen }.forEach { cat ->
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(cat.id in chosen,{chosen=if(it) chosen+cat.id else chosen-cat.id})
                Text(categoryDisplayName(cat.id,cat.name))
            }
        }
        Button(enabled=chosen.isNotEmpty(),onClick={onSave(setOf(item.id),chosen,false)},modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.virtual_save_categories))
        }
        OutlinedButton(onClick={onSave(setOf(item.id),chosen,true)},modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.virtual_defer))
        }
        OutlinedButton(onClick={onReanalyze(setOf(item.id))},modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.virtual_reanalyze)) }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Switch(item.isExcludedFromTransfer,{onExclude(item.id,it)})
            Text(stringResource(R.string.toggle_exclude_transfer))
        }
        Button(enabled=!item.needsManualReview&&!item.isExcludedFromTransfer&&item.sha256Checksum!=null&&
            item.analysisStatus in setOf("PROPOSED","APPROVED"),
            onClick={onOrganize(setOf(item.id))},modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.virtual_plan)) }
        if(item.timestampStatusNote!=null) Text(item.timestampStatusNote,color=StatusAmber)
    }
    if(viewer) FullMediaViewer(item) { viewer=false }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable fun FullMediaViewer(item: MediaItemEntity,onDismiss: ()->Unit) {
    val context=LocalContext.current
    val diagnostic=remember(item.id) { Diagnostics.operation(DiagnosticKind.GALLERY) }
    LaunchedEffect(item.id) { diagnostic.event(DiagnosticStage.FULLSCREEN_OPEN) }
    var scale by remember { mutableFloatStateOf(1f) }
    var x by remember { mutableFloatStateOf(0f) };var y by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val uri=item.destinationUriString ?: item.uriString
    val request=remember(uri,item.sha256Checksum) { ImageRequest.Builder(context).data(uri)
        .size(2048,2048).videoFrameMillis(1000).memoryCacheKey(uri+":"+item.sha256Checksum+":viewer2048").build() }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId=true }.testTag("full-media-viewer").background(AmoledPureBlack).windowInsetsPadding(WindowInsets.safeDrawing)) {
            AsyncImage(request,contentDescription=if(loaded) item.fileName else null,contentScale=ContentScale.Fit,
                onSuccess={loaded=true;error=false;diagnostic.event(DiagnosticStage.FULLSCREEN_OPEN,DiagnosticStatus.SUCCEEDED)},onError={error=true;loaded=false;diagnostic.event(DiagnosticStage.FULLSCREEN_OPEN,DiagnosticStatus.FAILED)},modifier=Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _,pan,zoom,_ ->
                        scale=(scale*zoom).coerceIn(1f,5f)
                        x=if(scale==1f) 0f else (x+pan.x).coerceIn(-3000f,3000f)
                        y=if(scale==1f) 0f else (y+pan.y).coerceIn(-3000f,3000f)
                    }
                }.graphicsLayer(scaleX=scale,scaleY=scale,translationX=x,translationY=y))
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(AmoledPureBlack.copy(alpha=.8f)).padding(12.dp)) {
                Text(item.fileName)
                if(error) Text(stringResource(R.string.virtual_preview_unavailable))
                TextButton(onClick=onDismiss) { Text(stringResource(R.string.btn_back)) }
                if(item.isVideo) OutlinedButton(onClick={
                    try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri),item.mimeType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch(_: Exception) { error=true }
                }) { Text(stringResource(R.string.virtual_open_video)) }
            }
        }
    }
}
