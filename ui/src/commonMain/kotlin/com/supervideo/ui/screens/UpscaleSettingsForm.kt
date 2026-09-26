package com.supervideo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.supervideo.core.model.UpscaleModel
import com.supervideo.core.settings.RateControl
import com.supervideo.core.settings.UpscaleSettings
import com.supervideo.core.settings.VideoCodec
import com.supervideo.core.settings.X264_PRESETS
import com.supervideo.core.upscale.Backend
import com.supervideo.core.upscale.Precision
import com.supervideo.core.video.EncoderSupport
import com.supervideo.ui.form.LabeledDropdown
import com.supervideo.ui.form.LabeledIntSlider
import com.supervideo.ui.form.LabeledTextField
import com.supervideo.ui.form.Section
import com.supervideo.ui.form.SwitchRow
import com.supervideo.ui.theme.spacing

private val OUTPUT_HEIGHTS: List<Int?> = listOf(null, 720, 1080, 1440, 2160)

/** Model / inference / encoding options shared by the Home screen and the defaults in Settings. */
@Composable
fun UpscaleSettingsForm(settings: UpscaleSettings, onChange: (UpscaleSettings) -> Unit) {
    val codecs = remember { VideoCodec.entries.filter { EncoderSupport.isAvailable(it) } }
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.level5)) {
        Section("Model") {
            LabeledDropdown(
                label = "Upscaling model",
                options = UpscaleModel.entries,
                selected = settings.model,
                optionText = { it.displayName },
                onSelect = { onChange(settings.copy(model = it)) },
            )
            if (settings.model.family == UpscaleModel.Family.RRDB) {
                Text(
                    "RRDB models take minutes per frame on phones; use them for short clips only.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Section("Inference") {
            LabeledDropdown(
                label = "Backend",
                options = Backend.entries,
                selected = settings.backend,
                optionText = { it.displayName },
                onSelect = { onChange(settings.copy(backend = it)) },
            )
            LabeledDropdown(
                label = "Precision",
                options = Precision.entries,
                selected = settings.precision,
                optionText = { it.displayName },
                onSelect = { onChange(settings.copy(precision = it)) },
            )
            LabeledIntSlider(
                label = "Tile size",
                value = settings.effectiveTileSize,
                range = UpscaleSettings.MIN_TILE..UpscaleSettings.MAX_TILE,
                step = UpscaleSettings.TILE_STEP,
                onChange = { onChange(settings.copy(tileSize = it)) },
                valueText = { if (settings.tileSize == null) "$it px (model default)" else "$it px" },
            )
            if (settings.tileSize != null) {
                TextButton(onClick = { onChange(settings.copy(tileSize = null)) }) {
                    Text("Reset to model default (${settings.model.defaultTileSize} px)")
                }
            }
            LabeledIntSlider(
                label = "Tile padding",
                value = settings.tilePadding,
                range = 0..UpscaleSettings.MAX_PADDING,
                step = 1,
                onChange = { onChange(settings.copy(tilePadding = it)) },
                valueText = { "$it px" },
            )
        }

        Section("Output") {
            LabeledDropdown(
                label = "Codec",
                options = codecs,
                selected = settings.codec.takeIf { it in codecs } ?: codecs.first(),
                optionText = { it.displayName },
                onSelect = { onChange(settings.copy(codec = it)) },
            )
            val isCrf = settings.rateControl is RateControl.Crf
            LabeledDropdown(
                label = "Quality mode",
                options = listOf(true, false),
                selected = isCrf,
                optionText = { if (it) "Constant quality (CRF)" else "Target bitrate" },
                onSelect = { crf ->
                    if (crf != isCrf) {
                        onChange(settings.copy(rateControl = if (crf) RateControl.Crf(18) else RateControl.Bitrate(20_000)))
                    }
                },
            )
            when (val rc = settings.rateControl) {
                is RateControl.Crf -> LabeledIntSlider(
                    label = "CRF (lower = better)",
                    value = rc.value,
                    range = 0..51,
                    step = 1,
                    onChange = { onChange(settings.copy(rateControl = RateControl.Crf(it))) },
                )
                is RateControl.Bitrate -> LabeledTextField(
                    label = "Bitrate (kbit/s)",
                    value = rc.kbps.toString(),
                    onChange = { text ->
                        text.filter(Char::isDigit).toIntOrNull()?.let {
                            onChange(settings.copy(rateControl = RateControl.Bitrate(it.coerceIn(100, 500_000))))
                        }
                    },
                )
            }
            LabeledDropdown(
                label = "Encoder preset",
                options = X264_PRESETS,
                selected = settings.preset,
                optionText = { it },
                onSelect = { onChange(settings.copy(preset = it)) },
            )
            LabeledDropdown(
                label = "Output height",
                options = OUTPUT_HEIGHTS,
                selected = settings.outputHeight,
                optionText = { it?.let { h -> "${h}p" } ?: "Native (model scale)" },
                onSelect = { onChange(settings.copy(outputHeight = it)) },
            )
            SwitchRow(
                label = "Copy audio",
                checked = settings.copyAudio,
                onChange = { onChange(settings.copy(copyAudio = it)) },
                description = "Stream-copies MP4-compatible audio tracks unchanged",
            )
        }
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}
