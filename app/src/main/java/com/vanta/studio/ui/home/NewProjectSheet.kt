package com.vanta.studio.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vanta.studio.R
import com.vanta.studio.domain.model.AspectRatio
import com.vanta.studio.domain.model.CanvasSpec
import com.vanta.studio.ui.components.Chip
import com.vanta.studio.ui.components.SectionLabel
import com.vanta.studio.ui.theme.Vanta

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProjectSheet(onDismiss: () -> Unit, onCreate: (String, CanvasSpec, List<Uri>) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val defaultName = stringResource(R.string.project_default_name)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var aspect by rememberSaveable { mutableStateOf(AspectRatio.R9_16) }
    var customW by rememberSaveable { mutableStateOf("21") }
    var customH by rememberSaveable { mutableStateOf("9") }
    var fps by rememberSaveable { mutableIntStateOf(30) }

    fun canvas() = CanvasSpec(
        aspect = aspect,
        customW = customW.toIntOrNull()?.coerceIn(1, 100) ?: 16,
        customH = customH.toIntOrNull()?.coerceIn(1, 100) ?: 9,
        fps = fps,
    )

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        onCreate(name.ifBlank { defaultName }, canvas(), uris)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Vanta.Surface, dragHandle = null) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.new_project_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 12.dp),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.new_project_name)) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Vanta.Accent, unfocusedBorderColor = Vanta.Outline),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(16.dp))
            SectionLabel(stringResource(R.string.new_project_aspect), Modifier.padding(start = 4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AspectRatio.entries.forEach { a ->
                    AspectCard(a, selected = a == aspect, onClick = { aspect = a })
                }
            }
            if (aspect == AspectRatio.CUSTOM) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumberField(customW, { customW = it }, Modifier.width(90.dp))
                    Text("  :  ", color = Vanta.TextDim)
                    NumberField(customH, { customH = it }, Modifier.width(90.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            SectionLabel(stringResource(R.string.new_project_fps), Modifier.padding(start = 4.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(24, 25, 30, 60).forEach { f -> Chip("$f fps", f == fps, { fps = f }) }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onCreate(name.ifBlank { defaultName }, canvas(), emptyList()) }) {
                    Text(stringResource(R.string.new_project_empty), color = Vanta.TextDim)
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier
                        .height(50.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Vanta.Accent)
                        .clickable {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PhotoLibrary, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.new_project_pick), style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(3)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Vanta.Accent, unfocusedBorderColor = Vanta.Outline),
        modifier = modifier,
    )
}

@Composable
private fun AspectCard(a: AspectRatio, selected: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) Vanta.Accent else Vanta.Outline, label = "aspectBorder")
    val (label, hint) = when (a) {
        AspectRatio.R16_9 -> "16:9" to stringResource(R.string.aspect_hint_16_9)
        AspectRatio.R9_16 -> "9:16" to stringResource(R.string.aspect_hint_9_16)
        AspectRatio.R1_1 -> "1:1" to stringResource(R.string.aspect_hint_1_1)
        AspectRatio.R4_5 -> "4:5" to stringResource(R.string.aspect_hint_4_5)
        AspectRatio.CUSTOM -> stringResource(R.string.aspect_custom) to stringResource(R.string.aspect_hint_custom)
    }
    val ratio = if (a == AspectRatio.CUSTOM) 1.4f else a.w.toFloat() / a.h
    Column(
        Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Vanta.AccentDim else Vanta.Surface2)
            .border(1.5.dp, border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            val mod = if (ratio >= 1f) Modifier.width(44.dp).aspectRatio(ratio) else Modifier.height(44.dp).aspectRatio(ratio)
            Box(
                mod
                    .clip(RoundedCornerShape(5.dp))
                    .border(
                        2.dp,
                        if (selected) Vanta.AccentBright else Vanta.TextDim,
                        RoundedCornerShape(5.dp),
                    ),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Vanta.AccentBright else Vanta.Text)
        Text(hint, style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, maxLines = 1)
    }
}
