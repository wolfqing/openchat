package org.openchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.Conversation
import org.openchat.android.app.PeerLabel
import org.openchat.android.app.Social
import org.openchat.android.mesh.MeshPeer
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Pseudo conversation key for the interactive mesh topology screen. */
const val TOPOLOGY = "topology"

internal enum class TopologyEdgeKind { DIRECT, ANNOUNCED, INFERRED }

internal data class TopologyNode(
    val peerHex: String,
    val nickname: String,
    val isSelf: Boolean,
    val ring: Int,
    val hops: Int?,
    /** Normalized radial coordinates: self at (0, 0), hop ring k at radius k. */
    val x: Float,
    val y: Float,
    val peer: MeshPeer? = null,
)

internal data class TopologyEdge(
    val fromHex: String,
    val toHex: String,
    val kind: TopologyEdgeKind,
)

internal data class TopologyGraph(
    val selfHex: String,
    val nodes: List<TopologyNode>,
    val edges: List<TopologyEdge>,
    val maxRing: Int,
)

/**
 * Pure radial topology layout centered on this device (`ring 0`).
 *
 * Ring 1 holds direct BLE neighbours; rings 2..4 hold multi-hop peers positioned near their
 * known or observed upstream relay to reduce edge crossings. Edges honestly distinguish
 * authoritative direct links (`DIRECT`), peer-announced neighbour links (`ANNOUNCED`), and
 * locally observed first-hop ingress relays (`INFERRED`).
 */
internal fun buildTopologyGraph(
    myPeerHex: String,
    myNickname: String,
    peers: List<MeshPeer>,
): TopologyGraph {
    val others = peers.filter { it.peerIdHex != myPeerHex }
    val ringOf = HashMap<String, Int>(others.size + 1)
    ringOf[myPeerHex] = 0
    for (p in others) {
        ringOf[p.peerIdHex] = when {
            p.direct -> 1
            p.hops != null -> p.hops.coerceIn(2, 4)
            else -> 2
        }
    }

    val nodes = ArrayList<TopologyNode>(others.size + 1)
    nodes += TopologyNode(
        peerHex = myPeerHex,
        nickname = myNickname,
        isSelf = true,
        ring = 0,
        hops = 0,
        x = 0f,
        y = 0f,
    )

    val angleOf = HashMap<String, Double>()
    val byRing = others.groupBy { ringOf[it.peerIdHex] ?: 2 }
    val maxRing = maxOf(1, byRing.keys.maxOrNull() ?: 1)

    // Place Ring 1 stably clockwise from 12 o'clock.
    val ring1 = byRing[1].orEmpty().sortedBy { it.peerIdHex }
    if (ring1.isNotEmpty()) {
        val step = 2.0 * PI / ring1.size
        ring1.forEachIndexed { idx, p ->
            val a = -PI / 2.0 + idx * step
            angleOf[p.peerIdHex] = a
            nodes += TopologyNode(
                peerHex = p.peerIdHex,
                nickname = p.nickname,
                isSelf = false,
                ring = 1,
                hops = 1,
                x = cos(a).toFloat(),
                y = sin(a).toFloat(),
                peer = p,
            )
        }
    }

    // Place outer rings grouped near their closer-ring relay when known.
    for (r in 2..maxRing) {
        val group = byRing[r].orEmpty()
        if (group.isEmpty()) continue
        fun preferredAngle(p: MeshPeer): Double? {
            val announcedParent = p.neighbors.firstOrNull { (ringOf[it] ?: Int.MAX_VALUE) < r && it in angleOf }
            if (announcedParent != null) return angleOf[announcedParent]
            val via = p.heardViaPeerHex
            if (via != null && via in angleOf) return angleOf[via]
            return null
        }

        val ordered = group.sortedWith(
            compareBy<MeshPeer> { preferredAngle(it) ?: Double.MAX_VALUE }
                .thenBy { it.peerIdHex },
        )
        val radius = r.toFloat()
        if (ordered.size == 1) {
            val p = ordered[0]
            val a = preferredAngle(p) ?: (-PI / 2.0 + (r - 1) * 0.65)
            angleOf[p.peerIdHex] = a
            nodes += TopologyNode(p.peerIdHex, p.nickname, false, r, p.hops, (radius * cos(a)).toFloat(), (radius * sin(a)).toFloat(), p)
        } else {
            val step = 2.0 * PI / ordered.size
            // Offset outer ring slightly so radial lines pass cleanly between inner nodes.
            val baseOffset = -PI / 2.0 + step * 0.5 * (r % 2)
            ordered.forEachIndexed { idx, p ->
                val rawSlot = baseOffset + idx * step
                val pref = preferredAngle(p)
                val a = if (pref != null) {
                    val delta = atan2(sin(pref - rawSlot), cos(pref - rawSlot))
                    if (kotlin.math.abs(delta) < step * 0.42) rawSlot + delta * 0.45 else rawSlot
                } else rawSlot
                angleOf[p.peerIdHex] = a
                nodes += TopologyNode(p.peerIdHex, p.nickname, false, r, p.hops, (radius * cos(a)).toFloat(), (radius * sin(a)).toFloat(), p)
            }
        }
    }

    val present = ringOf.keys
    val seenPairs = HashSet<Pair<String, String>>()
    val edges = ArrayList<TopologyEdge>()
    fun addEdge(a: String, b: String, kind: TopologyEdgeKind) {
        if (a == b || a !in present || b !in present) return
        val key = if (a < b) a to b else b to a
        if (seenPairs.add(key)) edges += TopologyEdge(a, b, kind)
    }

    // 1. Authoritative direct BLE edges to self.
    for (p in others) {
        if (p.direct) addEdge(myPeerHex, p.peerIdHex, TopologyEdgeKind.DIRECT)
    }
    // 2. Peer-announced neighbour edges (carried by bitchat peers).
    for (p in others) {
        for (n in p.neighbors) {
            if (n != myPeerHex) addEdge(p.peerIdHex, n, TopologyEdgeKind.ANNOUNCED)
        }
    }
    // 3. Locally observed first-hop ingress relay edge when no announced upstream edge exists.
    val hasAnnouncedUpstream = HashSet<String>()
    for (e in edges) {
        if (e.kind == TopologyEdgeKind.ANNOUNCED) {
            val rA = ringOf[e.fromHex] ?: 0
            val rB = ringOf[e.toHex] ?: 0
            if (rA < rB) hasAnnouncedUpstream += e.toHex
            else if (rB < rA) hasAnnouncedUpstream += e.fromHex
        }
    }
    for (p in others) {
        if (p.direct || p.peerIdHex in hasAnnouncedUpstream) continue
        val via = p.heardViaPeerHex ?: continue
        addEdge(via, p.peerIdHex, TopologyEdgeKind.INFERRED)
    }

    return TopologyGraph(selfHex = myPeerHex, nodes = nodes, edges = edges, maxRing = maxRing)
}

/**
 * Shortest path of peer hexes from `graph.selfHex` to [targetHex], preferring authoritative
 * (`DIRECT` / `ANNOUNCED`) edges before `INFERRED` edges, or null if disconnected.
 */
internal fun shortestTopologyPath(graph: TopologyGraph, targetHex: String): List<String>? {
    if (targetHex == graph.selfHex) return listOf(graph.selfHex)
    val adj = HashMap<String, MutableList<String>>()
    val orderedEdges = graph.edges.sortedBy { it.kind.ordinal }
    for (e in orderedEdges) {
        adj.getOrPut(e.fromHex) { ArrayList() }.add(e.toHex)
        adj.getOrPut(e.toHex) { ArrayList() }.add(e.fromHex)
    }
    val parent = HashMap<String, String?>()
    val queue = ArrayDeque<String>()
    parent[graph.selfHex] = null
    queue.addLast(graph.selfHex)
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        for (next in adj[cur].orEmpty()) {
            if (next in parent) continue
            parent[next] = cur
            if (next == targetHex) {
                val path = ArrayList<String>()
                var node: String? = targetHex
                while (node != null) {
                    path += node
                    node = parent[node]
                }
                return path.reversed()
            }
            queue.addLast(next)
        }
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshTopologyScreen(
    onBack: () -> Unit,
    onPanic: () -> Unit,
    onOpenPrivate: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val peers by ChatRepository.peers.collectAsState()
    val mesh by ChatRepository.mesh.collectAsState()
    val social by Social.state.collectAsState()
    val now = rememberNow()
    val locale = appLocale()
    val still = rememberReducedMotion()
    val density = LocalDensity.current

    val graph = remember(mesh.myPeerHex, mesh.myNickname, peers) {
        buildTopologyGraph(mesh.myPeerHex.ifEmpty { "self" }, mesh.myNickname, peers)
    }
    val otherCount = graph.nodes.size - 1
    val directCount = remember(peers, mesh.myPeerHex) {
        peers.count { it.peerIdHex != mesh.myPeerHex && it.direct }
    }

    var selectedHex by remember { mutableStateOf<String?>(null) }
    var inspectPerson by remember { mutableStateOf<PersonRef.Mesh?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val transformed = scale != 1f || pan != Offset.Zero

    // Clear selection if the peer drops out of range.
    val selectedNode = remember(graph, selectedHex) {
        selectedHex?.let { h -> graph.nodes.firstOrNull { !it.isSelf && it.peerHex == h } }
    }
    val highlightedPath = remember(graph, selectedNode?.peerHex) {
        selectedNode?.peerHex?.let { shortestTopologyPath(graph, it) }
    }
    val highlightedEdges = remember(highlightedPath) {
        val path = highlightedPath ?: return@remember emptySet<Pair<String, String>>()
        buildSet {
            for (i in 0 until path.size - 1) {
                val a = path[i]
                val b = path[i + 1]
                add(if (a < b) a to b else b to a)
            }
        }
    }

    inspectPerson?.let { p ->
        PersonSheet(
            person = p,
            onDismiss = { inspectPerson = null },
            onMessage = { hex ->
                inspectPerson = null
                onOpenPrivate(Conversation.Private(hex).key)
            },
        )
    }

    val pulse = if (still) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "topology-pulse")
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearOutSlowInEasing), RepeatMode.Restart),
            label = "ring",
        )
        p
    }

    val isLight = OpenChatPalette.isLight
    val signal = OpenChatPalette.Signal
    val online = OpenChatPalette.Online
    val ink = OpenChatPalette.Ink
    val muted = OpenChatPalette.Muted
    val ringColor = MaterialTheme.colorScheme.outlineVariant
    val edgeAnnouncedColor = OpenChatPalette.Outline
    val nodeFill = OpenChatPalette.CardSurface
    val labelBg = if (isLight) Color.White.copy(alpha = 0.88f) else Color(0xFF141518).copy(alpha = 0.88f)
    val measurer = rememberTextMeasurer()
    val horizontalSafe = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    val selfLabel = stringResource(R.string.group_you)
    val hopRingLabels = List(4) { i -> hopsText(i + 1) }

    // Precompute emblem colors & 5x5 cell patterns for every visible node.
    val nodeVisuals = remember(graph.nodes, isLight) {
        graph.nodes.associate { n ->
            n.peerHex to (Emblem.cells(n.peerHex) to Emblem.colorIndex(n.peerHex))
        }
    }
    val nodeColors = graph.nodes.associate { n ->
        n.peerHex to if (n.isSelf) signal else Emblem.uiColor(n.peerHex)
    }

    Box(Modifier.fillMaxSize().openChatCanvas()) {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = OpenChatPalette.Ink,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Column(Modifier.windowInsetsPadding(horizontalSafe)) {
                    TopAppBar(
                        colors = chromeTopBarColors(),
                        navigationIcon = { GlassBackButton(onBack) },
                        title = {
                            Column {
                                PanicTitle(stringResource(R.string.topology_title), onPanic)
                                Text(
                                    stringResource(R.string.nearby_peers_summary, directCount, otherCount),
                                    style = TabularMonoStyle,
                                    fontSize = 12.sp,
                                    color = muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        actions = {
                            if (transformed) {
                                IconButton(onClick = { scale = 1f; pan = Offset.Zero }) {
                                    Icon(
                                        Icons.Outlined.CenterFocusWeak,
                                        contentDescription = stringResource(R.string.topology_reset),
                                        tint = ink,
                                    )
                                }
                            }
                        },
                    )
                }
            },
        ) { padding ->
            val topClearance = padding.calculateTopPadding()
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(horizontalSafe)
                    .padding(top = topClearance),
            ) {
                var canvasSize by remember { mutableStateOf(Size.Zero) }
                val ringUnitPx: (Size) -> Float = { sz ->
                    val usable = minOf(sz.width, sz.height) * 0.42f
                    val rings = maxOf(2, graph.maxRing)
                    (usable / rings).coerceIn(with(density) { 56.dp.toPx() }, with(density) { 126.dp.toPx() })
                }
                val screenPos: (TopologyNode, Size) -> Offset = { node, sz ->
                    val cx = sz.width * 0.5f + pan.x
                    val cy = sz.height * 0.46f + pan.y
                    val unit = ringUnitPx(sz) * scale
                    Offset(cx + node.x * unit, cy + node.y * unit)
                }

                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, drag, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(0.6f, 2.6f)
                                scale = nextScale
                                pan += drag
                            }
                        }
                        .pointerInput(graph, scale, pan) {
                            detectTapGestures { tap ->
                                if (canvasSize.width <= 0f) return@detectTapGestures
                                val hitRadius = with(density) { 32.dp.toPx() }
                                val hit = graph.nodes
                                    .filter { !it.isSelf }
                                    .minByOrNull { n ->
                                        val pos = screenPos(n, canvasSize)
                                        hypot(pos.x - tap.x, pos.y - tap.y)
                                    }
                                    ?.takeIf { n ->
                                        val pos = screenPos(n, canvasSize)
                                        hypot(pos.x - tap.x, pos.y - tap.y) <= hitRadius
                                    }
                                selectedHex = if (hit != null && hit.peerHex == selectedHex) null else hit?.peerHex
                            }
                        },
                ) {
                    canvasSize = size
                    val cx = size.width * 0.5f + pan.x
                    val cy = size.height * 0.46f + pan.y
                    val center = Offset(cx, cy)
                    val unit = ringUnitPx(size) * scale
                    val visibleRings = maxOf(2, graph.maxRing)
                    val ringDash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
                    val inferredDash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 6.dp.toPx()))

                    // 1. Concentric hop rings + top ring labels.
                    val ringLabelStyle = TextStyle(
                        fontFamily = TabularMonoStyle.fontFamily,
                        fontFeatureSettings = "tnum",
                        fontSize = 10.5.sp,
                        color = muted.copy(alpha = 0.85f),
                    )
                    for (r in 1..visibleRings) {
                        val rad = r * unit
                        drawCircle(
                            color = ringColor.copy(alpha = if (r == 1) 0.95f else 0.65f),
                            radius = rad,
                            center = center,
                            style = Stroke(width = 1.dp.toPx(), pathEffect = if (r == 1) null else ringDash),
                        )
                        val label = hopRingLabels.getOrElse(r - 1) { "${r}h" }
                        val measured = measurer.measure(label, ringLabelStyle)
                        drawText(
                            measured,
                            topLeft = Offset(cx - measured.size.width * 0.5f, cy - rad + 4.dp.toPx()),
                        )
                    }

                    // 2. Subtle breathing pulse from this device.
                    if (!still) {
                        val pulseMax = unit * (if (otherCount == 0) 1.45f else 0.72f)
                        val pulseR = 20.dp.toPx() + pulseMax * pulse
                        drawCircle(
                            color = signal.copy(alpha = (1f - pulse) * (if (otherCount == 0) 0.35f else 0.22f)),
                            radius = pulseR,
                            center = center,
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }

                    val posMap = graph.nodes.associate { it.peerHex to screenPos(it, size) }

                    // 3. Edges (non-highlighted first, then highlighted path on top).
                    val sortedEdges = graph.edges.sortedBy { e ->
                        val k = if (e.fromHex < e.toHex) e.fromHex to e.toHex else e.toHex to e.fromHex
                        if (k in highlightedEdges) 1 else 0
                    }
                    for (e in sortedEdges) {
                        val p0 = posMap[e.fromHex] ?: continue
                        val p1 = posMap[e.toHex] ?: continue
                        val key = if (e.fromHex < e.toHex) e.fromHex to e.toHex else e.toHex to e.fromHex
                        val onPath = key in highlightedEdges
                        val dimmed = highlightedEdges.isNotEmpty() && !onPath
                        val color = when {
                            onPath -> signal
                            e.kind == TopologyEdgeKind.DIRECT -> online.copy(alpha = if (dimmed) 0.25f else 0.85f)
                            e.kind == TopologyEdgeKind.ANNOUNCED -> edgeAnnouncedColor.copy(alpha = if (dimmed) 0.25f else 0.90f)
                            else -> signal.copy(alpha = if (dimmed) 0.18f else 0.55f)
                        }
                        val width = when {
                            onPath -> 2.8.dp.toPx()
                            e.kind == TopologyEdgeKind.DIRECT -> 2.dp.toPx()
                            else -> 1.5.dp.toPx()
                        }
                        drawLine(
                            color = color,
                            start = p0,
                            end = p1,
                            strokeWidth = width,
                            cap = StrokeCap.Round,
                            pathEffect = if (e.kind == TopologyEdgeKind.INFERRED && !onPath) inferredDash else null,
                        )
                    }

                    // 4. Nodes + miniature 5x5 cryptographic emblems + labels.
                    val pathSet = highlightedPath?.toSet().orEmpty()
                    val nameStyle = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = ink)
                    for (node in graph.nodes) {
                        val pos = posMap[node.peerHex] ?: continue
                        val isSelected = node.peerHex == selectedHex
                        val onPath = node.peerHex in pathSet
                        val accent = nodeColors[node.peerHex] ?: signal
                        val nodeR = if (node.isSelf) 22.dp.toPx() else if (isSelected) 21.dp.toPx() else 18.dp.toPx()

                        if (isSelected) {
                            drawCircle(
                                color = signal.copy(alpha = 0.20f),
                                radius = nodeR + 7.dp.toPx(),
                                center = pos,
                            )
                        }

                        drawCircle(color = nodeFill, radius = nodeR, center = pos)
                        drawCircle(
                            color = when {
                                isSelected || onPath -> signal
                                node.isSelf -> signal
                                node.peer?.direct == true -> online
                                else -> accent.copy(alpha = 0.78f)
                            },
                            radius = nodeR,
                            center = pos,
                            style = Stroke(width = if (node.isSelf || isSelected || onPath) 2.5.dp.toPx() else 1.8.dp.toPx()),
                        )

                        // Draw the 5x5 mirrored identity emblem inside the node.
                        val cells = nodeVisuals[node.peerHex]?.first
                        if (cells != null) {
                            val gridSize = nodeR * 1.12f
                            val step = gridSize / 5f
                            val dot = (step * 0.78f).coerceAtLeast(1.5f)
                            val dotR = CornerRadius(dot * 0.35f, dot * 0.35f)
                            val left = pos.x - gridSize * 0.5f
                            val top = pos.y - gridSize * 0.5f
                            for (i in 0 until 25) if (cells[i]) {
                                drawRoundRect(
                                    color = accent,
                                    topLeft = Offset(left + (i % 5) * step + (step - dot) * 0.5f, top + (i / 5) * step + (step - dot) * 0.5f),
                                    size = Size(dot, dot),
                                    cornerRadius = dotR,
                                )
                            }
                        }

                        // Compact pill label beneath each node.
                        val displayName = if (node.isSelf) {
                            "$selfLabel · ${node.nickname.take(10)}"
                        } else {
                            val trimmed = if (node.nickname.length > 10) node.nickname.take(9) + "…" else node.nickname
                            "$trimmed#${node.peerHex.take(4)}"
                        }
                        val measured = measurer.measure(displayName, nameStyle, maxLines = 1)
                        val padH = 6.dp.toPx()
                        val padV = 2.dp.toPx()
                        val boxW = measured.size.width + padH * 2
                        val boxH = measured.size.height + padV * 2
                        val boxTop = pos.y + nodeR + 5.dp.toPx()
                        drawRoundRect(
                            color = labelBg,
                            topLeft = Offset(pos.x - boxW * 0.5f, boxTop),
                            size = Size(boxW, boxH),
                            cornerRadius = CornerRadius(boxH * 0.5f, boxH * 0.5f),
                        )
                        drawText(
                            measured,
                            topLeft = Offset(pos.x - measured.size.width * 0.5f, boxTop + padV),
                        )
                    }
                }

                // Floating legend pill at top-center of the canvas.
                FlatCapsule(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LegendSwatch(color = online, dashed = false, label = stringResource(R.string.band_direct))
                        LegendSwatch(color = edgeAnnouncedColor, dashed = false, label = stringResource(R.string.topology_legend_known))
                        LegendSwatch(color = signal, dashed = true, label = stringResource(R.string.topology_legend_relay))
                    }
                }

                // Empty state footer card when scanning alone.
                if (otherCount == 0) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = OpenChatPalette.CardSurface,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.topology_empty_hint),
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp,
                            color = muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        )
                    }
                }

                // Selected peer inspector card docked at the bottom.
                AnimatedVisibility(
                    visible = selectedNode?.peer != null,
                    enter = fadeIn(tween(150)) + slideInVertically(tween(180)) { it / 2 },
                    exit = fadeOut(tween(120)) + slideOutVertically(tween(140)) { it / 2 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    val peer = selectedNode?.peer ?: return@AnimatedVisibility
                    val hex = peer.peerIdHex
                    val blocked = social.isBlockedPeer(hex)
                    val fav = social.favoriteOf(hex)
                    val inlineIcons = rememberLabelInlineIcons()
                    val nodeByHex = remember(graph.nodes) { graph.nodes.associateBy { it.peerHex } }
                    val pathText = remember(highlightedPath, nodeByHex, selfLabel) {
                        val p = highlightedPath ?: return@remember null
                        if (p.size <= 2) null
                        else p.joinToString(" → ") { h ->
                            val n = nodeByHex[h]
                            when {
                                n == null -> h.take(4)
                                n.isSelf -> selfLabel
                                else -> PeerLabel.mesh(n.nickname, n.peerHex).toString()
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = OpenChatPalette.CardSurface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CompositionLocalProvider(LocalOnElevatedSurface provides true) {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    EmblemView(hex, 40.dp)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            labelText(
                                                PeerLabel.mesh(peer.nickname, hex),
                                                verified = social.isVerifiedPeer(hex),
                                                vouched = social.isVouchedPeer(hex),
                                                starred = fav?.mine == true,
                                            ),
                                            inlineContent = inlineIcons,
                                            fontSize = 15.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val hopStr = when {
                                            peer.direct || peer.hops == 1 -> stringResource(R.string.hops_direct)
                                            peer.hops != null -> hopsText(peer.hops)
                                            else -> stringResource(R.string.dm_relayed)
                                        }
                                        Text(
                                            "$hopStr · ${agoText(peer.lastSeen, now, locale)}",
                                            style = TabularMonoStyle,
                                            fontSize = 12.sp,
                                            color = if (peer.direct) online else signal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    FluidGlassCircleButton(
                                        icon = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.close),
                                        onClick = { selectedHex = null },
                                        size = 34.dp,
                                        iconSize = 17.dp,
                                        iconTint = muted,
                                    )
                                }

                                if (pathText != null) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = OpenChatPalette.Well,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            pathText,
                                            style = TabularMonoStyle,
                                            fontSize = 11.5.sp,
                                            lineHeight = 16.sp,
                                            color = muted,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        )
                                    }
                                }

                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    TonalActionButton(
                                        onClick = { inspectPerson = PersonRef.Mesh(hex, peer.nickname) },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                                    ) {
                                        Icon(Icons.Outlined.PersonOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.topology_inspect), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (!blocked) {
                                        Button(
                                            onClick = { onOpenPrivate(Conversation.Private(hex).key) },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = OpenChatPalette.Lock,
                                                contentColor = Color.White,
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.peer_message), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendSwatch(color: Color, dashed: Boolean, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Canvas(Modifier.size(width = 16.dp, height = 6.dp)) {
            val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null
            drawLine(
                color = color,
                start = Offset(0f, size.height * 0.5f),
                end = Offset(size.width, size.height * 0.5f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = effect,
            )
        }
        Text(
            label,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = OpenChatPalette.Muted,
            maxLines = 1,
        )
    }
}
