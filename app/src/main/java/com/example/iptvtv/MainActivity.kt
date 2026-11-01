package com.example.iptvtv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val PLAYLIST_URL = "https://iptv-org.github.io/iptv/index.m3u"
const val HOME_COUNTRY = "Sri Lanka" // default until the user picks another one

// Well-known channels per country (matched as whole words, in this order).
// Edit freely: add or remove names to change what shows up in "Popular".
val POPULAR: Map<String, List<String>> = mapOf(
    "Sri Lanka" to listOf("derana", "hiru", "sirasa", "tv1", "swarnavahini", "rupavahini", "itn", "siyatha", "shakthi", "sakthi", "vasantham", "supreme", "channel eye", "newsfirst", "tv 1"),
    "India" to listOf("aaj tak", "ndtv", "zee news", "zee tv", "star plus", "sony", "colors", "republic", "india tv", "abp", "news18", "dd national", "dd news", "sun tv", "times now", "cnbc"),
    "Pakistan" to listOf("geo", "ary", "hum", "samaa", "dunya", "express", "bol", "ptv"),
    "Bangladesh" to listOf("btv", "somoy", "jamuna", "channel i", "ntv", "atn", "independent"),
    "Maldives" to listOf("psm", "tvm"),
    "Nepal" to listOf("nepal television", "kantipur", "image", "ap1"),
    "United Kingdom" to listOf("bbc one", "bbc two", "bbc news", "itv", "channel 4", "sky news", "channel 5", "gb news", "bbc three", "bbc four", "cbbc"),
    "United States" to listOf("abc news", "cbs news", "nbc news", "cnn", "fox news", "bloomberg", "nasa", "cnbc", "pbs", "abc", "cbs", "nbc", "fox"),
    "Canada" to listOf("cbc", "ctv", "global", "citytv", "tsn"),
    "Australia" to listOf("abc", "sbs", "nine", "seven", "network 10"),
    "Qatar" to listOf("al jazeera", "bein"),
    "United Arab Emirates" to listOf("dubai", "abu dhabi", "sharjah", "mbc", "al arabiya"),
    "Singapore" to listOf("cna", "channel news asia", "channel 5", "channel 8"),
    "Malaysia" to listOf("tv1", "tv2", "tv3", "astro", "bernama"),
    "Japan" to listOf("nhk", "tokyo mx", "fuji"),
    "France" to listOf("france 24", "tf1", "bfm", "france 2", "france 3"),
    "Germany" to listOf("dw", "zdf", "ard", "tagesschau", "welt", "n-tv")
)

data class Channel(
    val name: String,
    val logo: String?,
    val groups: List<String>,
    val countryName: String,
    val countryCode: String,
    val url: String,
    val blocked: Boolean,
    val quality: Int,
    val rank: Int
)

fun hasWord(text: String, kw: String): Boolean =
    Regex("(^|[^a-z0-9])" + Regex.escape(kw) + "($|[^a-z0-9])").containsMatchIn(text)

fun rankOf(cleanName: String, country: String): Int {
    val kws = POPULAR[country] ?: return -1
    val n = cleanName.lowercase()
    return kws.indexOfFirst { hasWord(n, it) }
}

private val tagRe = Regex("\\s*\\[[^\\]]*\\]")
private val qualRe = Regex("\\s*\\(\\d{3,4}[pi]\\)")
private val pRe = Regex("(\\d{3,4})p")

fun cleanName(raw: String): String = raw.replace(tagRe, "").replace(qualRe, "").trim().ifBlank { raw }
fun qualityOf(raw: String): Int = pRe.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0

fun countryOf(tvgId: String?): Pair<String, String> {
    if (tvgId.isNullOrBlank()) return Pair("Other", "--")
    var code = tvgId.substringBefore('@').substringAfterLast('.').uppercase()
    if (code.length != 2) return Pair("Other", "--")
    if (code == "UK") code = "GB"
    val n = Locale("", code).getDisplayCountry(Locale.ENGLISH)
    return if (n.isBlank() || n == code) Pair("Other", "--") else Pair(n, code)
}

fun parseM3u(text: String): List<Channel> {
    val list = ArrayList<Channel>()
    var name = ""
    var logo: String? = null
    var groups: List<String> = emptyList()
    var country = Pair("Other", "--")
    for (line in text.lineSequence()) {
        val l = line.trim()
        when {
            l.startsWith("#EXTINF") -> {
                name = l.substringAfterLast(",").trim()
                logo = Regex("tvg-logo=\"(.*?)\"").find(l)?.groupValues?.get(1)
                val g = Regex("group-title=\"(.*?)\"").find(l)?.groupValues?.get(1)
                groups = g?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?.ifEmpty { null } ?: listOf("Undefined")
                country = countryOf(Regex("tvg-id=\"(.*?)\"").find(l)?.groupValues?.get(1))
            }
            l.isNotEmpty() && !l.startsWith("#") -> {
                val raw = name.ifBlank { l }
                val clean = cleanName(raw)
                list.add(
                    Channel(
                        clean, logo?.ifBlank { null }, groups.ifEmpty { listOf("Undefined") },
                        country.first, country.second, l,
                        raw.contains("Geo-blocked", ignoreCase = true), qualityOf(raw),
                        rankOf(clean, country.first)
                    )
                )
                name = ""; logo = null; groups = emptyList(); country = Pair("Other", "--")
            }
        }
    }
    return dedupe(list)
}

// Same channel listed several times (different qualities/feeds): keep the best one.
fun dedupe(list: List<Channel>): List<Channel> {
    val m = LinkedHashMap<String, Channel>()
    for (c in list) {
        val k = c.name.lowercase() + "|" + c.countryName
        val o = m[k]
        if (o == null || (if (c.blocked != o.blocked) !c.blocked else c.quality > o.quality)) m[k] = c
    }
    return m.values.toList()
}

fun pickTop(list: List<Channel>): Pair<List<Channel>, Boolean> {
    val curated = list.filter { it.rank >= 0 }.sortedBy { it.rank }.take(12)
    if (curated.isNotEmpty()) return Pair(curated, true)
    return Pair(list.filter { it.logo != null && it.quality >= 720 }.take(8), false)
}

fun download(url: String): String {
    val c = URL(url).openConnection()
    c.connectTimeout = 15000
    c.readTimeout = 60000
    return c.getInputStream().bufferedReader().use { it.readText() }
}

val Accent = Color(0xFF6C8CFF)
val Gold = Color(0xFFFFB547)
val Bg = Color(0xFF0F1115)
val Panel = Color(0xFF171B26)
val Card = Color(0xFF1E222B)
val BgBrush = Brush.verticalGradient(listOf(Color(0xFF1A2038), Color(0xFF0F1115)))
val CardBrush = Brush.verticalGradient(listOf(Color(0xFF283048), Color(0xFF1B2030)))

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
    val prefs = remember { ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE) }
    var home by remember { mutableStateOf(prefs.getString("home", HOME_COUNTRY) ?: HOME_COUNTRY) }
    var all by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var status by remember { mutableStateOf("Loading channels...") }
    var loaded by remember { mutableStateOf(false) }
    var minTime by remember { mutableStateOf(false) }
    var mode by remember { mutableIntStateOf(1) }
    var selected by remember { mutableStateOf<String?>(home) }
    var query by remember { mutableStateOf("") }
    var sideQuery by remember { mutableStateOf("") }
    var hideBlocked by remember { mutableStateOf(true) }
    var showPicker by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<Pair<List<Channel>, Int>?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { delay(2500); minTime = true }

    LaunchedEffect(refreshKey) {
        val cache = File(ctx.cacheDir, "playlist.m3u")
        if (refreshKey == 0 && cache.exists()) {
            all = withContext(Dispatchers.IO) { parseM3u(cache.readText()) }
            loaded = true
            status = "updating..."
        }
        try {
            val text = withContext(Dispatchers.IO) { download(PLAYLIST_URL) }
            withContext(Dispatchers.IO) { cache.writeText(text) }
            all = withContext(Dispatchers.IO) { parseM3u(text) }
            status = "up to date"
        } catch (e: Exception) {
            status = if (all.isEmpty()) "Error: ${e.message}" else "offline, showing saved list"
        }
        loaded = true
    }

    val visible = remember(all, hideBlocked) { if (hideBlocked) all.filter { !it.blocked } else all }
    val codeByName = remember(all) { all.associate { it.countryName to it.countryCode } }

    val countryEntries = remember(visible, home) {
        visible.groupingBy { it.countryName }.eachCount().toList()
            .sortedBy { it.first }.sortedByDescending { it.first == home }
    }
    val categoryEntries = remember(visible) {
        visible.flatMap { it.groups }.groupingBy { it }.eachCount().toList().sortedWith(
            compareBy({ p -> pinned.indexOf(p.first).let { if (it < 0) 99 else it } }, { it.first })
        )
    }
    val entries = if (mode == 0) categoryEntries else countryEntries
    val shownEntries = remember(entries, sideQuery) {
        if (sideQuery.isBlank()) entries else entries.filter { it.first.contains(sideQuery, ignoreCase = true) }
    }

    val base = remember(visible, mode, selected, query, home) {
        visible.filter { c ->
            (selected == null || (if (mode == 0) selected in c.groups else c.countryName == selected)) &&
                (query.isBlank() || c.name.contains(query, ignoreCase = true))
        }.sortedWith(
            compareBy<Channel>({ it.countryName != home }, { it.logo == null }, { -it.quality }, { it.name })
        )
    }

    val showTop = mode == 1 && selected != null && query.isBlank()
    val top = remember(base, showTop) { if (showTop) pickTop(base) else Pair(emptyList<Channel>(), false) }
    val rest = remember(base, top) { if (top.first.isEmpty()) base else base.filter { it !in top.first } }
    val playList = remember(top, rest) { top.first + rest }

    fun pickHome(name: String) {
        home = name
        prefs.edit().putString("home", name).apply()
        mode = 1; selected = name; sideQuery = ""; query = ""
    }

    if (!(minTime && loaded)) {
        Splash(status)
        return
    }

    val current = playing
    if (current != null) {
        BackHandler { playing = null }
        PlayerScreen(current.first, current.second)
        return
    }

    if (showPicker) {
        CountryPicker(countryEntries, home, { pickHome(it); showPicker = false }, { showPicker = false })
    }

    Box(Modifier.fillMaxSize().background(BgBrush)) {
        Row(Modifier.fillMaxSize().padding(16.dp)) {
            Column(
                Modifier.width(280.dp).fillMaxHeight()
                    .clip(RoundedCornerShape(32.dp)).background(Panel).padding(18.dp)
            ) {
                Text("IPTV", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tab("Categories", mode == 0, Modifier.weight(1f)) { mode = 0; selected = null; sideQuery = "" }
                    Tab("Countries", mode == 1, Modifier.weight(1f)) { mode = 1; selected = home; sideQuery = "" }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = sideQuery, onValueChange = { sideQuery = it },
                    placeholder = { Text(if (mode == 0) "Search category" else "Search country") },
                    singleLine = true, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    item { SideItem("All", visible.size, selected == null) { selected = null } }
                    items(shownEntries) { (name, count) ->
                        SideItem(name, count, selected == name) { selected = name }
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text("Search channels") }, singleLine = true,
                        shape = RoundedCornerShape(28.dp), modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = { hideBlocked = !hideBlocked }, shape = RoundedCornerShape(24.dp)) {
                        Text(if (hideBlocked) "Blocked: hidden" else "Blocked: shown")
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = { status = "updating..."; refreshKey++ }, shape = RoundedCornerShape(24.dp)) {
                        Text("Refresh")
                    }
                    Spacer(Modifier.width(12.dp))
                    CountryCircle(codeByName[home] ?: "--") { showPicker = true }
                }
                Text(
                    "${selected ?: "All"}  -  ${base.size} channels  -  $status",
                    Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodyMedium, color = Color.Gray
                )
                LazyVerticalGrid(GridCells.Adaptive(190.dp), Modifier.fillMaxSize()) {
                    if (top.first.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionTitle(if (top.second) "Popular in $selected" else "Featured in $selected")
                        }
                        items(top.first) { ch -> ChannelCard(ch) { playing = Pair(playList, playList.indexOf(ch)) } }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionTitle("All channels (${rest.size})")
                        }
                    }
                    items(rest) { ch -> ChannelCard(ch) { playing = Pair(playList, playList.indexOf(ch)) } }
                }
            }
        }
    }
}

@Composable
fun Splash(status: String) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val date = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date(now))
    val time = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(now))
    Box(Modifier.fillMaxSize().background(BgBrush)) {
        Column(Modifier.align(Alignment.TopCenter).padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date, style = MaterialTheme.typography.titleLarge, color = Color.LightGray)
            Text(time, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Gold)
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(110.dp).clip(CircleShape).background(CardBrush).border(3.dp, Gold, CircleShape),
                contentAlignment = Alignment.Center
            ) { Text("TV", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Gold) }
            Spacer(Modifier.height(24.dp))
            Text("TV STARTING...", fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(color = Gold)
            Spacer(Modifier.height(14.dp))
            Text(status, color = Color.Gray)
        }
    }
}

@Composable
fun CountryCircle(code: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.size(56.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape).background(CardBrush)
            .border(if (focused) 4.dp else 2.dp, if (focused) Color.White else Gold, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(code, fontWeight = FontWeight.Bold, color = Gold, fontSize = 18.sp) }
}

@Composable
fun CountryPicker(
    entries: List<Pair<String, Int>>, home: String,
    onPick: (String) -> Unit, onDismiss: () -> Unit
) {
    var q by remember { mutableStateOf("") }
    val list = remember(entries, q) {
        if (q.isBlank()) entries else entries.filter { it.first.contains(q, ignoreCase = true) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(440.dp).clip(RoundedCornerShape(32.dp)).background(Panel).padding(22.dp)
        ) {
            Text("Choose your country", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("The app opens on this country's channels.", color = Color.Gray)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = q, onValueChange = { q = it }, placeholder = { Text("Search country") },
                singleLine = true, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(list) { (name, count) -> SideItem(name, count, name == home) { onPick(name) } }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Row(Modifier.padding(start = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(6.dp).height(22.dp).clip(RoundedCornerShape(3.dp)).background(Gold))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Gold)
    }
}

@Composable
fun Tab(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.clip(RoundedCornerShape(24.dp))
            .onFocusChanged { focused = it.isFocused }
            .background(if (active) Accent else Card)
            .border(if (focused) 2.dp else 0.dp, Color.White, RoundedCornerShape(24.dp))
            .clickable { onClick() }.padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) Color.Black else Color.White) }
}

@Composable
fun SideItem(name: String, count: Int, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) Accent.copy(alpha = 0.35f) else if (active) Card else Color.Transparent)
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp),
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
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "scale")
    Column(
        Modifier.padding(6.dp).scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 0.dp, Gold, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp)).background(CardBrush)
            .clickable { onClick() }.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.height(84.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x14FFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            if (ch.logo == null) {
                Text(ch.name.take(1).uppercase(), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Gold)
            } else {
                AsyncImage(model = ch.logo, contentDescription = null,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(10.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        Text(ch.countryName, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

@Composable
fun PlayerScreen(list: List<Channel>, start: Int) {
    val ctx = LocalContext.current
    var index by remember { mutableIntStateOf(start.coerceIn(0, list.size - 1)) }
    var tick by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    val ch = list[index]
    val player = remember { ExoPlayer.Builder(ctx).build() }
    val focus = remember { FocusRequester() }

    fun go(d: Int) { index = (index + d + list.size) % list.size }

    LaunchedEffect(index) {
        error = null
        buffering = true
        player.setMediaItem(MediaItem.fromUri(list[index].url))
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(index, tick) {
        info = true
        delay(3500)
        info = false
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
            }
            override fun onPlayerError(e: PlaybackException) {
                error = "This channel is not available right now. Press Up or Down for another channel."
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                    when (e.key) {
                        Key.DirectionUp, Key.ChannelUp -> { go(1); true }
                        Key.DirectionDown, Key.ChannelDown -> { go(-1); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { tick++; true }
                        else -> false
                    }
                } else false
            }
            .focusRequester(focus).focusable()
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    useController = false
                    isFocusable = false
                    keepScreenOn = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Gold)
        error?.let {
            Text(it, Modifier.align(Alignment.Center).padding(32.dp), color = Color.White, fontSize = 20.sp)
        }
        if (info) {
            Column(
                Modifier.align(Alignment.TopStart).padding(28.dp)
                    .clip(RoundedCornerShape(24.dp)).background(Color(0xCC0F1115)).padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(ch.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "${ch.countryName}  -  ${index + 1} / ${list.size}  -  Up / Down: change channel",
                    color = Gold, fontSize = 14.sp
                )
            }
        }
    }
}
