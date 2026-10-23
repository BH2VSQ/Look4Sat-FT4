package com.rtbishop.look4sat.feature.radar

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rtbishop.look4sat.feature.cw.CwNativeCapability
import com.rtbishop.look4sat.feature.cw.MorseExpertAudioBridge
import com.rtbishop.look4sat.core.domain.audio.IAudioHub
import com.rtbishop.look4sat.core.presentation.R
import com.ve3nea.morse_expert.MainActivity
import kotlinx.coroutines.CancellationException
import com.rtbishop.look4sat.feature.cw.R as CwR

internal val LocalRadarCwDecoder = compositionLocalOf<RadarCwDecoder?> { null }
internal val LocalCwConsoleVisible = compositionLocalOf { false }

private tailrec fun Context.activity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> error("CW decoder requires an Activity")
}

internal class RadarCwDecoder(private val activity: Activity) {
    val controller = MainActivity()
    val view: ConstraintLayout by lazy {
        (LayoutInflater.from(activity).inflate(CwR.layout.cw_panel_main, null) as ConstraintLayout).also { root ->
            // Native text remains laid out for its decoder's editable buffer; Compose renders it.
            root.findViewById<TextView>(CwR.id.decodedTextView).apply {
                minimumHeight = 1
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                alpha = 0f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
    }
    private var bound = false

    fun start(text: String, onText: (String) -> Unit) {
        if (!bound) {
            controller.onCreate(activity, view, false)
            bound = true
            controller.restoreDecodedText(text)
            controller.onPermissionGranted()
        }
        controller.setDecodedTextListener(onText)
        controller.onResume()
    }

    fun clear() { if (bound) controller.clearDecoded() }
    fun pause() { if (bound) controller.onPause() }
    fun dispose() {
        if (bound) {
            controller.setDecodedTextListener(null)
            controller.onPause()
            controller.onDestroy()
            bound = false
        }
    }
}

@Composable
internal fun rememberRadarCwDecoder(cw: CwSubState, audioHub: IAudioHub, onAction: (RadarAction) -> Unit,
    onLeaveSession: () -> Unit): RadarCwDecoder? {
    val context = LocalContext.current
    val activity = remember(context) { context.activity() }
    val action by rememberUpdatedState(onAction)
    val leave by rememberUpdatedState(onLeaveSession)
    val currentText by rememberUpdatedState(cw.decodedText)
    val decoder = remember(activity) { if (CwNativeCapability.isAvailable) RadarCwDecoder(activity) else null }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, decoder) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !activity.isChangingConfigurations) {
                action(RadarAction.CwStopListening)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            decoder?.dispose()
            if (!activity.isChangingConfigurations) leave()
        }
    }
    LaunchedEffect(decoder, cw.status) {
        if (decoder != null && cw.status == CwStatus.Listening) {
            try {
                decoder.start(currentText) { action(RadarAction.CwDecodedTextChanged(it)) }
                MorseExpertAudioBridge(audioHub, decoder.controller).collect()
                action(RadarAction.CwStopListening)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                action(RadarAction.CwDecoderFailed(context.getString(R.string.cw_console_decode_failed)))
            } catch (_: LinkageError) {
                action(RadarAction.CwDecoderFailed(context.getString(R.string.cw_console_decode_failed)))
            } finally {
                decoder.pause()
            }
        }
    }
    LaunchedEffect(decoder, cw.resetGeneration) { if (cw.decodedText.isEmpty()) decoder?.clear() }
    return decoder
}

@Composable
internal fun SharedCwDecoderPanel(
    cw: CwSubState,
    onAction: (RadarAction) -> Unit,
    requestMicPermission: () -> Unit,
    onCallsign: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val decoder = LocalRadarCwDecoder.current
    val background = MaterialTheme.colorScheme.surfaceContainer.toArgb()
    val scaleColor = MaterialTheme.colorScheme.outline.toArgb()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (cw.status == CwStatus.Listening) onAction(RadarAction.CwStopListening)
                else if (cw.hasPermission) onAction(RadarAction.CwStartListening)
                else requestMicPermission()
            }, modifier = Modifier.weight(1f)) {
                Text(stringResource(if (cw.status == CwStatus.Listening) R.string.radar_cw_stop else R.string.radar_cw_start))
            }
            OutlinedButton(onClick = { onAction(RadarAction.CwReset) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.radar_cw_reset))
            }
        }
        if (decoder != null && cw.status == CwStatus.Listening) {
            AndroidView(factory = {
                decoder.view.also { (it.parent as? ViewGroup)?.removeView(it) }
            }, update = { root ->
                root.setBackgroundColor(background)
                root.findViewById<View>(CwR.id.scaleView).setBackgroundColor(scaleColor)
            }, modifier = Modifier.fillMaxWidth().height(84.dp))
        } else if (decoder == null) {
            Text("${cw.cwToneFreq.toInt()} Hz", style = MaterialTheme.typography.labelMedium)
            Slider(value = cw.cwToneFreq.coerceIn(300f, 1200f),
                onValueChange = { onAction(RadarAction.CwSetToneFreq(it)) }, valueRange = 300f..1200f)
        }
        val candidates = remember(cw.decodedText) { cwCallsignCandidates(cw.decodedText) }
        val primary = MaterialTheme.colorScheme.primary
        val text = remember(cw.decodedText, candidates, primary) {
            buildAnnotatedString {
                append(cw.decodedText)
                if (onCallsign != null) candidates.forEach {
                    addStyle(SpanStyle(color = primary), it.start, it.end)
                }
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 200.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp)
            .verticalScroll(rememberScrollState())) {
            if (text.isEmpty()) Text(stringResource(R.string.radar_cw_waiting))
            else ClickableText(text = text, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)) { offset ->
                candidates.firstOrNull { offset >= it.start && offset < it.end }?.let { onCallsign?.invoke(it.callsign) }
            }
        }
        cw.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
