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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.auralis.player.addons.AddonClient
import app.auralis.player.addons.AddonTrack
import app.auralis.player.playback.PlaybackService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val AuralisBackground = Color(0xFF0D0D12)
private val AuralisSurface = Color(0xFF191923)
private val AuralisAccent = Color(0xFFB9A6FF)
private val AuralisMint = Color(0xFF85DCCB)

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var queue by mutableStateOf<List<MediaItem>>(emptyList())
    private var playing by mutableStateOf(false)
    private var currentTitle by mutableStateOf("Nothing playing")
    private var currentArtist by mutableStateOf("Import music or connect a provider")
    private var status by mutableStateOf("Ready")
    private var providerUrl by mutableStateOf("")
    private var providerToken by mutableStateOf("")
    private var query by mutableStateOf("")
    private var providerName by mutableStateOf("No provider connected")
    private var results by mutableStateOf<List<AddonTrack>>(emptyList())
    private var busy by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

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
                primary = AuralisAccent,
                secondary = AuralisMint,
                background = AuralisBackground,
                surface = AuralisSurface
            )) {
                Surface(Modifier.fillMaxSize(), color = AuralisBackground) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("A U R A L I S", color = AuralisAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Music, without boundaries.", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            }
                            FilledTonalIconButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                                Icon(Icons.Default.Add, contentDescription = "Import local music")
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        NowPlayingCard(
                            title = currentTitle,
                            artist = currentArtist,
                            status = status,
                            playing = playing,
                            onPrevious = { controller?.seekToPreviousMediaItem() },
                            onToggle = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                            onNext = { controller?.seekToNextMediaItem() }
                        )
                        Spacer(Modifier.height(18.dp))
                        Text("PROVIDER", color = AuralisAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text(providerName, color = Color.LightGray, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = providerUrl, onValueChange = { providerUrl = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Provider HTTPS base URL") },
                            placeholder = { Text("https://provider.example") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )
                        OutlinedTextField(
                            value = providerToken, onValueChange = { providerToken = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Provider token (optional)") },
                            visualTransformation = PasswordVisualTransformation()
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(enabled = !busy, onClick = { connectProvider() }) { Text("CONNECT") }
                        }
                        OutlinedTextField(
                            value = query, onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Search tracks") },
                            trailingIcon = {
                                IconButton(enabled = !busy && query.isNotBlank(), onClick = { searchProvider() }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search provider")
                                }
                            }
                        )
                        if (busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = AuralisAccent)
                        }
                        errorMessage?.let {
                            Text(it, color = Color(0xFFFF9B9B), fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                        }
                        Text("RESULTS  ·  ${results.size}", color = AuralisAccent, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 14.dp)) {
                            items(results) { track ->
                                ProviderTrackRow(track = track, onClick = { playTrack(track) })
                            }
                        }
                    }
                }
            }
        }
    }

    private fun newClient(): AddonClient {
        val url = providerUrl.trim().trimEnd('/')
        require(url.startsWith("https://", ignoreCase = true)) { "Enter a valid HTTPS provider URL." }
        return AddonClient(url, providerToken.trim().takeIf { it.isNotEmpty() })
    }

    private fun connectProvider() {
        lifecycleScope.launch {
            busy = true
            errorMessage = null
            try {
                val manifest = withContext(Dispatchers.IO) { newClient().manifest() }
                providerName = "${manifest.name} · ${manifest.version}"
                status = "Provider connected"
            } catch (e: Exception) {
                providerName = "Connection failed"
                errorMessage = e.message ?: "Could not connect to provider"
            } finally { busy = false }
        }
    }

    private fun searchProvider() {
        lifecycleScope.launch {
            busy = true
            errorMessage = null
            try {
                results = withContext(Dispatchers.IO) { newClient().search(query.trim()) }
                if (results.isEmpty()) status = "No matching tracks"
            } catch (e: Exception) {
                errorMessage = e.message ?: "Provider search failed"
            } finally { busy = false }
        }
    }

    private fun playTrack(track: AddonTrack) {
        val stream = track.streamUrl
        if (stream.isNullOrBlank() || !stream.startsWith("https://", ignoreCase = true)) {
            errorMessage = "This provider did not return a playable HTTPS stream URL for this track."
            return
        }
        val item = MediaItem.Builder().setUri(stream).setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .build()
        ).build()
        controller?.let { player ->
            player.setMediaItem(item)
            player.prepare()
            player.play()
            currentTitle = track.title
            currentArtist = track.artist
            errorMessage = null
        } ?: run { errorMessage = "Playback service is still starting. Try again." }
    }

    private fun importUris(uris: List<Uri>) {
        val items = uris.map { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            MediaItem.Builder().setUri(uri).setMediaMetadata(
                MediaMetadata.Builder().setTitle(uri.lastPathSegment ?: "Local audio").build()
            ).build()
        }
        controller?.let { player ->
            player.setMediaItems(items)
            player.prepare()
            player.play()
        }
        queue = items
        status = "${items.size} local track(s) queued"
    }

    private fun syncQueue() {
        val player = controller ?: return
        queue = (0 until player.mediaItemCount).map(player::getMediaItemAt)
    }
}

@Composable
private fun NowPlayingCard(
    title: String, artist: String, status: String, playing: Boolean,
    onPrevious: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF211F30)),
        shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("NOW PLAYING", color = AuralisAccent, fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(10.dp))
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(artist, color = Color.LightGray, fontSize = 13.sp, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(status, color = AuralisMint, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White) }
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(50.dp)) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause")
                }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
            }
        }
    }
}

@Composable
private fun ProviderTrackRow(track: AddonTrack, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)
    ) {
        Text(track.title, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(
            listOfNotNull(track.artist, track.album).joinToString(" · "),
            color = Color.LightGray, fontSize = 12.sp, maxLines = 1
        )
        val quality = track.bitrate?.let { "${it / 1000} kbps" } ?: "Quality not reported"
        Text(quality, color = AuralisMint, fontSize = 10.sp)
    }
    HorizontalDivider(color = Color(0xFF30303B))
}
