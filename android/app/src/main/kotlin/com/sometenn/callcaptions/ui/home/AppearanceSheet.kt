package com.sometenn.callcaptions.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sometenn.callcaptions.core.CaptionFace
import com.sometenn.callcaptions.core.CaptionInk
import com.sometenn.callcaptions.core.CaptionDisplayMode
import com.sometenn.callcaptions.core.OverlayAppearance
import com.sometenn.callcaptions.core.OverlayFrame
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSheet(
    appearance: OverlayAppearance,
    previewFrame: OverlayFrame,
    displayMode: CaptionDisplayMode,
    overlayActive: Boolean,
    onUpdate: (OverlayAppearance) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "字幕与悬浮窗外观",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onDismiss) {
                    Text("完成")
                }
            }

            if (overlayActive) {
                Text(
                    text = "悬浮字幕已开启，拖动滑块会实时调整屏幕上的悬浮窗。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Live preview card
            OverlayPreviewCard(
                frame = previewFrame,
                displayMode = displayMode,
                appearance = appearance,
            )

            Text("字幕字体")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CaptionFace.entries.forEach { face ->
                    FilterChip(selected = face == appearance.face, onClick = { onUpdate(appearance.copy(face = face)) }, label = { Text(face.title) })
                }
            }
            Text("译文颜色")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CaptionInk.entries.forEach { ink ->
                    FilterChip(selected = ink == appearance.ink, onClick = { onUpdate(appearance.copy(ink = ink)) }, label = { Text(ink.title) })
                }
            }
            Text("日语字号 · ${appearance.sourceSize} sp")
            Slider(value = appearance.sourceSize.toFloat(), valueRange = 12f..34f,
                onValueChange = { onUpdate(appearance.copy(sourceSize = it.roundToInt())) })
            Text("中文字号 · ${appearance.translationSize} sp")
            Slider(value = appearance.translationSize.toFloat(), valueRange = 16f..52f,
                onValueChange = { onUpdate(appearance.copy(translationSize = it.roundToInt())) })

            // 1. Font scale
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("整体缩放", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${appearance.fontScalePercent}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Slider(
                    value = appearance.fontScale,
                    onValueChange = { onUpdate(appearance.copy(fontScale = it)) },
                    valueRange = OverlayAppearance.MIN_FONT_SCALE..OverlayAppearance.MAX_FONT_SCALE,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val presets = listOf(
                        "小" to 0.85f,
                        "标准" to 1.00f,
                        "大" to 1.25f,
                        "特大" to 1.50f,
                    )
                    presets.forEach { (label, scale) ->
                        FilterChip(
                            selected = abs(appearance.fontScale - scale) < 0.03f,
                            onClick = { onUpdate(appearance.copy(fontScale = scale)) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            // 2. Overlay width
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("悬浮框宽度", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "屏幕宽度的 ${appearance.widthPercent}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Slider(
                    value = appearance.widthPercent.toFloat(),
                    onValueChange = { onUpdate(appearance.copy(widthPercent = it.roundToInt())) },
                    valueRange = OverlayAppearance.MIN_WIDTH_PERCENT.toFloat()..OverlayAppearance.MAX_WIDTH_PERCENT.toFloat(),
                )
            }

            // 3. Overlay height (max lines)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("悬浮框高度（显示行数）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${appearance.maxLines} 行",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                val lineChoices = (OverlayAppearance.MIN_LINES..OverlayAppearance.MAX_LINES).toList()
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    lineChoices.forEachIndexed { index, lines ->
                        SegmentedButton(
                            selected = appearance.maxLines == lines,
                            onClick = { onUpdate(appearance.copy(maxLines = lines)) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = lineChoices.size),
                        ) {
                            Text("${lines}行")
                        }
                    }
                }
            }

            // 4. Background opacity
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("背景不透明度", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${appearance.backgroundAlphaPercent}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Slider(
                    value = appearance.backgroundAlphaPercent.toFloat(),
                    onValueChange = { onUpdate(appearance.copy(backgroundAlphaPercent = it.roundToInt())) },
                    valueRange = OverlayAppearance.MIN_BG_ALPHA_PERCENT.toFloat()..OverlayAppearance.MAX_BG_ALPHA_PERCENT.toFloat(),
                )
            }

            OutlinedButton(
                onClick = onReset,
                enabled = appearance != OverlayAppearance.DEFAULT,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("恢复默认外观")
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
