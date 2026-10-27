package com.example.myplayer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myplayer.usb.UsbVolume

/** 設定。今は USB DAC の初期音量だけ。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text("設定") })

        val initial by UsbVolume.initial.collectAsState()
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text("USB DAC の初期音量", style = MaterialTheme.typography.titleMedium)
            Text(
                "USB DAC に直接出力するとき、再生を始める音量です(DAC の音量。0 dB が最大)。" +
                    "「音量を保持する」が OFF のときは、再生中のスライダーで変えた音量も、アプリを終了すると初期音量に戻ります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = initial / 256f,
                    onValueChange = { UsbVolume.setInitial((it * 256).toInt()) },
                    valueRange = (UsbVolume.INITIAL_MIN_DB256 / 256f)..(UsbVolume.INITIAL_MAX_DB256 / 256f),
                    steps = (UsbVolume.INITIAL_MAX_DB256 - UsbVolume.INITIAL_MIN_DB256) / 256 - 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "%d dB".format(initial / 256),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(64.dp),
                )
            }
        }

        val keep by UsbVolume.keep.collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("音量を保持する", style = MaterialTheme.typography.titleMedium)
                Text(
                    "ON: 再生中に変えた音量を保存し、次回もその音量で始めます(保存がなければ初期音量)。" +
                        "OFF: 毎回、初期音量で始めます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Switch(checked = keep, onCheckedChange = { UsbVolume.setKeep(it) })
        }
    }
}
