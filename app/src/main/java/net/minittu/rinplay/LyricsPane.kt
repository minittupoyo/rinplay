package net.minittu.rinplay

import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minittu.rinplay.lyrics.LrcLine
import net.minittu.rinplay.lyrics.Lyrics
import net.minittu.rinplay.lyrics.LyricsQuery
import net.minittu.rinplay.lyrics.LyricsRepository
import net.minittu.rinplay.lyrics.LyricsUnavailableException

/**
 * 全画面「再生中」のジャケットの代わりに出す歌詞。
 *
 * 開いたときに、曲のメタデータ(タイトル・アーティスト・アルバム・長さ)で LRCLIB を探す。
 * 見つからないときは検索ボタンから手で探せる(曲名とアーティストは埋めておく)。
 * 同期つきの歌詞があれば、再生位置に合わせて今の行を強調して追いかける。
 */
@Composable
fun LyricsPane(
    repository: LyricsRepository,
    mediaId: String?,
    metadata: MediaMetadata,
    durationMs: Long,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val query = remember(metadata, durationMs) { metadata.toLyricsQuery(durationMs) }
    // 長さは曲の切り替え直後にはまだ来ていないことがあるので、常に最新を見る。
    val latestDuration by rememberUpdatedState(durationMs)

    var state by remember(mediaId) { mutableStateOf<LyricsUi>(LyricsUi.Loading) }
    var attempt by remember(mediaId) { mutableIntStateOf(0) }
    var searchOpen by remember(mediaId) { mutableStateOf(false) }

    LaunchedEffect(mediaId, attempt) {
        if (mediaId == null || query.title.isBlank()) {
            state = LyricsUi.NotFound
            return@LaunchedEffect
        }
        state = LyricsUi.Loading
        // LRCLIB の一致判定は長さ ±2 秒なので、長さが届くのを少しだけ待つ(それでも来なければ長さ無しで探す)。
        val deadline = System.currentTimeMillis() + DURATION_WAIT_MS
        while (latestDuration <= 0 && System.currentTimeMillis() < deadline) delay(100)
        state = try {
            repository.find(mediaId, query.copy(durationSec = latestDuration.secondsOrNull()))
                ?.let { LyricsUi.Found(it) }
                ?: LyricsUi.NotFound
        } catch (e: LyricsUnavailableException) {
            LyricsUi.Failed(e.message.orEmpty())
        }
    }

    if (searchOpen) {
        LyricsSearchDialog(
            repository = repository,
            initial = query,
            cacheKey = mediaId,
            onDismiss = { searchOpen = false },
            onSelect = { lyrics ->
                state = LyricsUi.Found(lyrics)
                searchOpen = false
            },
        )
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (val current = state) {
            LyricsUi.Loading -> CircularProgressIndicator()

            LyricsUi.NotFound -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "歌詞が見つかりませんでした",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = { searchOpen = true }) { Text("歌詞を検索") }
            }

            is LyricsUi.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    current.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                TextButton(onClick = { attempt++ }) { Text("再試行") }
            }

            is LyricsUi.Found -> LyricsBody(
                lyrics = current.lyrics,
                positionMs = positionMs,
                onSearch = { searchOpen = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 取得した歌詞。同期つきがあれば再生位置に合わせ、なければ同期なしの歌詞を出す。 */
@Composable
private fun LyricsBody(
    lyrics: Lyrics,
    positionMs: Long,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = lyrics.label.ifEmpty { "歌詞" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSearch) {
                Icon(Icons.Rounded.Search, contentDescription = "別の歌詞を検索")
            }
        }
        when {
            lyrics.instrumental -> Centered("インストゥルメンタル(歌詞なし)", Modifier.fillMaxSize())
            lyrics.synced.isNotEmpty() -> SyncedLyrics(lyrics.synced, positionMs, Modifier.fillMaxSize())
            lyrics.plain != null -> PlainLyrics(lyrics.plain, Modifier.fillMaxSize())
            else -> Centered("歌詞はありません", Modifier.fillMaxSize())
        }
    }
}

/** 同期つきの歌詞。いま鳴っている行を強調して、その行が見える位置まで送る。 */
@Composable
private fun SyncedLyrics(lines: List<LrcLine>, positionMs: Long, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val current = lines.indexOfLast { it.timeMs <= positionMs }

    LaunchedEffect(current) {
        if (current < 0 || listState.isScrollInProgress) return@LaunchedEffect
        // 画面の真ん中あたりに来るように送る。
        listState.animateScrollToItem(current, -listState.layoutInfo.viewportSize.height / 2 + 48)
    }

    LazyColumn(state = listState, modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(lines.size) { index ->
            val isCurrent = index == current
            Text(
                text = lines[index].text,
                style = if (isCurrent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 同期なしの歌詞。行ごとに出すだけ。 */
@Composable
private fun PlainLyrics(plain: String, modifier: Modifier = Modifier) {
    val lines = remember(plain) { plain.lines().map { it.trim() } }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(lines.size) { index ->
            Text(
                text = lines[index],
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 真ん中に 1 行だけ出す(見つからない・インストゥルメンタルなど)。 */
@Composable
private fun Centered(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 手動の歌詞検索。曲名とアーティストはメタデータの値で埋めておく。
 * 候補を選ぶと、その 1 件を取り直して表示に反映する。
 */
@Composable
private fun LyricsSearchDialog(
    repository: LyricsRepository,
    initial: LyricsQuery,
    cacheKey: String?,
    onDismiss: () -> Unit,
    onSelect: (Lyrics) -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var artist by remember { mutableStateOf(initial.artist.orEmpty()) }
    var results by remember { mutableStateOf<List<Lyrics>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runSearch() {
        if (title.isBlank() || busy) return
        busy = true
        message = null
        scope.launch {
            try {
                val found = repository.search(
                    LyricsQuery(title = title.trim(), artist = artist.trim().ifEmpty { null }),
                )
                results = found
                if (found.isEmpty()) message = "見つかりませんでした"
            } catch (e: LyricsUnavailableException) {
                results = emptyList()
                message = e.message
            } finally {
                busy = false
            }
        }
    }

    fun select(lyrics: Lyrics) {
        if (busy) return
        busy = true
        scope.launch {
            // 検索の結果にも歌詞は入っているが、本文の無いレコードもあるので、選んだ 1 件を取り直す。
            val full = try {
                repository.byId(lyrics.id) ?: lyrics
            } catch (e: LyricsUnavailableException) {
                lyrics
            }
            // 選んだ歌詞は、その曲のものとして覚えておく(次に再生したときは、これを出す)。
            cacheKey?.let { repository.save(it, full) }
            busy = false
            onSelect(full)
        }
    }

    LaunchedEffect(Unit) { runSearch() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("歌詞を検索", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("曲名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("アーティスト") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(
                    onClick = { runSearch() },
                    enabled = title.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text("検索") }

                Box(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp)) {
                    when {
                        busy -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        message != null -> Text(
                            message.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        )
                        else -> LazyColumn {
                            items(results.orEmpty(), key = { it.id }) { lyrics ->
                                LyricsCandidateRow(lyrics) { select(lyrics) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 検索結果の 1 行。曲名 / アーティスト · アルバム · 長さ。 */
@Composable
private fun LyricsCandidateRow(lyrics: Lyrics, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Album,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(lyrics.trackName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 「同期つき」は先頭に出す(後ろだと、長いアーティスト名で切れて見えなくなる)。
            val status = when {
                lyrics.instrumental -> "歌詞なし"
                lyrics.synced.isNotEmpty() -> "同期つき"
                else -> null
            }
            val detail = listOfNotNull(
                status,
                lyrics.artistName.ifEmpty { null },
                lyrics.albumName.ifEmpty { null },
                lyrics.durationSec.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60, it % 60) },
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 歌詞の状態。読み込み中 / 見つかった / 無い / 取れなかった。 */
private sealed interface LyricsUi {
    data object Loading : LyricsUi
    data object NotFound : LyricsUi
    data class Found(val lyrics: Lyrics) : LyricsUi
    data class Failed(val message: String) : LyricsUi
}

/** 長さ(ミリ秒)→ 秒。0 以下や不明は null。 */
private fun Long.secondsOrNull(): Int? = if (this > 0) (this / 1000).toInt() else null

/**
 * メタデータから検索条件を作る。タグの無いファイルは `<unknown>` や「不明な…」が入るので、落とす
 * (そのまま送ると、一致しなくなる)。
 */
private fun MediaMetadata.toLyricsQuery(durationMs: Long): LyricsQuery = LyricsQuery(
    title = title?.toString().orEmpty().trim(),
    artist = artist?.toString().tagOrNull(),
    album = albumTitle?.toString().tagOrNull(),
    durationSec = durationMs.secondsOrNull(),
)

private fun String?.tagOrNull(): String? {
    val value = this?.trim().orEmpty()
    return value.takeIf { it.isNotEmpty() && it != MediaStore.UNKNOWN_STRING && it !in UNKNOWN_TAGS }
}

private val UNKNOWN_TAGS = setOf(
    MusicRepository.UNKNOWN_ARTIST,
    MusicRepository.UNKNOWN_ALBUM,
    "unknown artist",
    "unknown album",
)

/** 長さがメタデータに載るまで待つ上限。 */
private const val DURATION_WAIT_MS = 1_500L
