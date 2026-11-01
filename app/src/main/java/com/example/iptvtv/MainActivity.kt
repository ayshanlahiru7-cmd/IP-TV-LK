package com.example.iptvtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.Locale

const val PLAYLIST_URL = "https://iptv-org.github.io/iptv/index.m3u"

data class Channel(
    val name: String,
    val logo: String?,
    val groups: List<String>,
    val countryName: String,
    val url: String
)

fun countryName(tvgId: String?): String {
    if (tvgId.isNullOrBlank()) return "Other"
    val code = tvgId.substringBefore('@').substringAfterLast('.').uppercase()
    if (code.length != 2) return "Other"
    if (code == "UK") return "United Kingdom"
    val n = Locale("", code).getDisplayCountry(Locale.ENGLISH)
    return if (n.isBlank() || n == code) "Other" else n
}

fun parseM3u(text: String): List<Channel> {
    val list = ArrayList<Channel>()
    var name = ""
    var logo: String? = null
    var groups: List<String> = emptyList()
    var country = "Other"
    for (line in text.lineSequence()) {
        val l = line.trim()
        when {
            l.startsWith("#EXTINF") -> {
                name = l.substringAfterLast(",").trim()
                logo = Regex("tvg-logo=\"(.*?)\"").find(l)?.groupValues?.get(1)
                val g = Regex("group-title=\"(.*?)\"").find(l)?.groupValues?.get(1)
                groups = g?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?.ifEmpty { null } ?: listOf("Undefined")
                country = countryName(Regex("tvg-id=\"(.*?)\"").find(l)?.groupValues?.get(1))
            }
            l.isNotEmpty() && !l.startsWith("#") -> {
                list.add(Channel(name.ifBlank { l }, logo, groups.ifEmpty { listOf("Undefined") }, country, l))
                name = ""; logo = null; groups = emptyList(); country = "Other"
            }
        }
    }
    return list
}

fun download(url: String): String {
    val c = URL(url).openConnection()
    c.connectTimeout = 15000
    c.readTimeout = 60000
    return c.getInputStream().bufferedReader().use { it.readText() }
}

val Accent = Color(0xFF6C8CFF)
val Bg = Color(0xFF0F1115)
val Panel = Color(0xFF171A21)
val Card = Color(0xFF1E222B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { App() }
            }
        }
    }
}

private val pinned = listOf("Movies", "Kids", "Animation", "Sports", "News", "Music", "Entertainment", "Documentary")

@Composable
fun App() {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var status by remember { mutableStateOf("Loading channels...") }
    var mode by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf<Channel?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        val cache = File(ctx.cacheDir, "playlist.m3u")
        if (refreshKey == 0 && cache.exists()) {
            all = withContext(Dispatchers.IO) { parseM3u(cache.readText()) }
            status = "${all.size} channels (updating...)"
        }
        try {
            val text = withContext(Dispatchers.IO) { download(PLAYLIST_URL) }
            withContext(Dispatchers.IO) { cache.writeText(text) }
            all = withContext(Dispatchers.IO) { parseM3u(text) }
            status = "${all.size} channels"
        } catch (e: Exception) {
            status = if (all.isEmpty()) "Error: ${e.message}" else "Offline - showing saved list"
        }
    }

    val current = playing
    if (current != null) {
        BackHandler { playing = null }
        PlayerScreen(current)
        return
    }

    val entries = remember(all, mode) {
        val src = if (mode == 0) all.flatMap { it.groups } else all.map { it.countryName }
        val counts = src.groupingBy { it }.eachCount().toList()
        if (mode == 0) counts.sortedWith(
            compareBy({ p -> pinned.indexOf(p.first).let { if (it < 0) 99 else it } }, { it.first })
        ) else counts.sortedBy { it.first }
    }
    val shown = remember(all, mode, selected, query) {
        all.filter { c ->
            (selected == null || (if (mode == 0) selected in c.groups else c.countryName == selected)) &&
                (query.isBlank() || c.name.contains(query, ignoreCase = true))
        }
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(280.dp).fillMaxHeight().background(Panel).padding(16.dp)) {
            Text("IPTV", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tab("Categories", mode == 0, Modifier.weight(1f)) { mode = 0; selected = null }
                Tab("Countries", mode == 1, Modifier.weight(1f)) { mode = 1; selected = null }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                item { SideItem("All", all.size, selected == null) { selected = null } }
                items(entries) { (name, count) ->
                    SideItem(name, count, selected == name) { selected = name }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Search channels") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Button(onClick = { status = "Updating..."; refreshKey++ }) { Text("Refresh") }
            }
            Text(
                "${selected ?: "All"}  -  ${shown.size} shown  -  $status",
                Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
            LazyVerticalGrid(GridCells.Adaptive(190.dp), Modifier.fillMaxSize()) {
                items(shown) { ch -> ChannelCard(ch) { playing = ch } }
            }
        }
    }
}

@Composable
fun Tab(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.clip(RoundedCornerShape(20.dp))
            .onFocusChanged { focused = it.isFocused }
            .background(if (active) Accent else Card)
            .border(if (focused) 2.dp else 0.dp, Color.White, RoundedCornerShape(20.dp))
            .clickable { onClick() }.padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) Color.Black else Color.White) }
}

@Composable
fun SideItem(name: String, count: Int, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) Accent.copy(alpha = 0.35f) else if (active) Card else Color.Transparent)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
        Text("$count", color = Color.Gray)
    }
}

@Composable
fun ChannelCard(ch: Channel, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "scale")
    Column(
        Modifier.padding(6.dp).scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 0.dp, Accent, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp)).background(Card)
            .clickable { onClick() }.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.height(80.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AsyncImage(model = ch.logo, contentDescription = null,
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(8.dp))
        Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        Text(ch.countryName, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

@Composable
fun PlayerScreen(ch: Channel) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(ch.url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = "This stream is not available right now. Press Back."
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = true } },
            modifier = Modifier.fillMaxSize()
        )
        error?.let { Text(it, Modifier.align(Alignment.Center), color = Color.White) }
    }
}
