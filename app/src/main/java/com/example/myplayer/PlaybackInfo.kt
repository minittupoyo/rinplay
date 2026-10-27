package com.example.myplayer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 再生情報のアイコンが表す状態(出力経路から決める)。 */
private enum class InfoStatus(
    val icon: ImageVector,
    /** 色付きの背景で目立たせるか(ビットパーフェクトのとき) */
    val highlighted: Boolean,
    val title: String,
) {
    USB_DIRECT(Icons.Rounded.Usb, true, "ビットパーフェクト(USB 直結)"),
    BIT_PERFECT(Icons.Rounded.HighQuality, true, "ビットパーフェクト"),
    MIXER(Icons.Rounded.GraphicEq, false, "標準出力(OS のミキサー経由)"),
    NONE(Icons.Rounded.GraphicEq, false, "出力情報なし"),
}

private fun OutputFormat?.status(): InfoStatus = when (this?.path) {
    OutputPath.USB_DIRECT -> InfoStatus.USB_DIRECT
    OutputPath.BIT_PERFECT -> InfoStatus.BIT_PERFECT
    OutputPath.MIXER -> InfoStatus.MIXER
    null -> InfoStatus.NONE
}

/**
 * 再生情報のボタン。アイコンが出力の状態を表す(ビットパーフェクトなら色付きの背景、標準出力なら無地、
 * 情報がなければ薄く)。タップで詳細のダイアログを開く。
 */
@Composable
fun PlaybackInfoButton(format: OutputFormat?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val status = format.status()
    val description = Modifier.semantics { contentDescription = "再生情報: ${status.title}" }
    if (status.highlighted) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = modifier.then(description),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Icon(status.icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
    } else {
        IconButton(onClick = onClick, modifier = modifier.then(description)) {
            Icon(
                status.icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (status == InfoStatus.NONE) 0.4f else 1f),
            )
        }
    }
}

/** 再生情報の詳細。再生中のファイルの形式と、出力の形式・経路。 */
@Composable
fun PlaybackInfoDialog(source: SourceFormat?, format: OutputFormat?, onDismiss: () -> Unit) {
    val status = format.status()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        title = { Text("再生情報") },
        text = {
            Column {
                InfoRow("状態", status.title)
                InfoRow("再生中のファイル", source?.label() ?: "—")
                InfoRow("出力形式", format?.fullLabel() ?: "—")
                InfoRow(
                    "ビットパーフェクト",
                    when (format?.path) {
                        OutputPath.USB_DIRECT, OutputPath.BIT_PERFECT -> "はい"
                        OutputPath.MIXER -> "いいえ" + format.bitPerfect.takeIf { it.isNotEmpty() }?.let { "($it)" }.orEmpty()
                        null -> "—"
                    },
                )
                Text(
                    text = when (format?.path) {
                        OutputPath.USB_DIRECT ->
                            "OS のミキサーを通さず、USB DAC に直接出力しています。音量は DAC 側で調整します。"
                        OutputPath.BIT_PERFECT ->
                            "OS のビットパーフェクト出力です。"
                        OutputPath.MIXER ->
                            "OS のミキサー経由です。レートの変換などが入ることがあります。"
                        null ->
                            "再生を始めると表示されます。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
