package app.auralis.player

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.auralis.player.playback.PlaybackService
import com.google.common.util.concurrent.MoreExecutors

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var queue by mutableStateOf<List<MediaItem>>(emptyList())
    private var playing by mutableStateOf(false)
    private var currentTitle by mutableStateOf("Nothing playing")
    private var currentArtist by mutableStateOf("Choose music from your device")
    private var status by mutableStateOf("Ready for your library")
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { c ->
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                    override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                        currentTitle = metadata.title?.toString() ?: "Unknown title"
                        currentArtist = metadata.artist?.toString() ?: "Unknown artist"
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        status = when (state) {
                            Player.STATE_BUFFERING -> "Buffering"
                            Player.STATE_READY -> "Ready"
                            Player.STATE_ENDED -> "Queue finished"
                            else -> "Idle"
                        }
                        syncQueue()
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        currentTitle = mediaItem?.mediaMetadata?.title?.toString() ?: "Unknown title"
                        currentArtist = mediaItem?.mediaMetadata?.artist?.toString() ?: "Unknown artist"
                        syncQueue()
                    }
                })
                syncQueue()
            }.onFailure { status = "Playback service unavailable: ${it.message}" }
        }, MoreExecutors.directExecutor())

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFB9A6FF), secondary = Color(0xFF85DCCB),
                background = Color(0xFF0D0D12), surface = Color(0xFF171720)
            )) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0D0D12)) {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("AURALIS", color = Color(0xFFB9A6FF), fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                                Text("Your music, your way.", color = Color.White, fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold)
                            }
                            FilledTonalIconButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                                Icon(Icons.Default.Add, contentDescription = "Add music")
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF211F30)),
                            shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(22.dp)) {
                                Text("NOW PLAYING", color = Color(0xFFB9A6FF), fontSize = 11.sp, letterSpacing = 2.sp)
                                Spacer(Modifier.height(34.dp))
                                Text(currentTitle, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                                Text(currentArtist, color = Color.LightGray, fontSize = 14.sp)
                                Spacer(Modifier.height(12.dp))
                                Text(status, color = Color(0xFF85DCCB), fontSize = 12.sp)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { controller?.seekToPreviousMediaItem() }) {
                                        Icon(Icons.Default.SkipPrevious, null, tint = Color.White)
                                    }
                                    FilledIconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                                        modifier = Modifier.size(58.dp)) {
                                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause")
                                    }
                                    IconButton(onClick = { controller?.seekToNextMediaItem() }) {
                                        Icon(Icons.Default.SkipNext, null, tint = Color.White)
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    TextButton(onClick = { controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false) }) {
                                        Icon(Icons.Default.Shuffle, null); Text("Shuffle")
                                    }
                                    TextButton(onClick = { controller?.repeatMode =
                                        if (controller?.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
                                    }) { Icon(Icons.Default.Repeat, null); Text("Repeat") }
                                }
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("UP NEXT", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("${queue.size} tracks", color = Color.Gray, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.weight(1f)) {
                            itemsIndexed(queue) { index, item ->
                                val title = item.mediaMetadata.title?.toString()
                                    ?: item.localConfiguration?.uri?.lastPathSegment ?: "Track ${index + 1}"
                                val artist = item.mediaMetadata.artist?.toString() ?: "Local audio"
                                Row(Modifier.fillMaxWidth().clickable {
                                    controller?.seekTo(index, 0); controller?.play()
                                }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("%02d".format(index + 1), color = Color(0xFF8E88A5), modifier = Modifier.width(38.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(title, color = Color.White, maxLines = 1)
                                        Text(artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                                    }
                                }
                                HorizontalDivider(color = Color(0xFF292832))
                            }
                        }
                        Text("Local playback • MediaSession background service", color = Color.Gray, fontSize = 10.sp)
                    }
                }
            }
        }
    }

    private fun importUris(uris: List<Uri>) {
        val items = uris.mapIndexed { index, uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            MediaItem.Builder().setUri(uri).setMediaMetadata(MediaMetadata.Builder()
                .setTitle(uri.lastPathSegment ?: "Track ${index + 1}")
                .setArtist("Local audio").build()).build()
        }
        queue = items
        controller?.setMediaItems(items)
        controller?.prepare()
        controller?.play()
        status = "Added ${items.size} tracks"
    }
    private fun syncQueue() {
        val c = controller ?: return
        queue = (0 until c.mediaItemCount).map(c::getMediaItemAt)
    }
    override fun onDestroy() {
        controller?.release()
        controller = null
        super.onDestroy()
    }
}
