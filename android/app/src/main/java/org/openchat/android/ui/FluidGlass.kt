package org.openchat.android.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openchat.android.R

/**
 * Optical parameters for OpenChat's in-tree Liquid Glass engine:
 * crisp curved-meniscus Snell refraction, subtle RGB chromatic dispersion,
 * micro clarity blur, and ultra-clear zero-fog interiors.
 */
object FluidGlassDefaults {
    val Refraction: Dp = 15.dp
    val Dispersion: Dp = 6.dp
    val ClarityBlur: Dp = 2.dp
    val MeniscusBevel: Dp = 16.dp
}

internal class RecordedBackdropSource(val layer: GraphicsLayer) {
    var hasRecorded: Boolean = false
}

@Stable
class FluidGlassHostState internal constructor() {
    internal var activeSource by mutableStateOf<RecordedBackdropSource?>(null)
    var sourceOffset by mutableStateOf(Offset.Zero)
    var sourceSize by mutableStateOf(Size.Zero)
    var hasSource by mutableStateOf(false)
    internal var recordingDepth: Int = 0
}

val LocalFluidGlassHost = compositionLocalOf<FluidGlassHostState?> { null }

/** True inside a [FluidGlassSource] subtree so nested glass pills never sample their own ancestor layer. */
val LocalInsideFluidGlassSource = compositionLocalOf { false }

/** Extra bottom padding reserved when the floating [CapsuleNavigationBar] sits over screen content. */
val LocalBottomBarClearance = compositionLocalOf { 0.dp }

/**
 * Provides a shared backdrop coordinate & layer host so floating chrome (top signal/live-PTT pills,
 * floating 3-piece composer, and floating bottom tab capsule) can optically refract live
 * scrolling content underneath without external dependencies or cyclic RenderNode graphs.
 */
@Composable
fun FluidGlassHost(content: @Composable () -> Unit) {
    val existing = LocalFluidGlassHost.current
    if (existing != null && !LocalInsideFluidGlassSource.current) {
        content()
        return
    }
    val state = remember { FluidGlassHostState() }
    CompositionLocalProvider(
        LocalFluidGlassHost provides state,
        LocalInsideFluidGlassSource provides false,
        content = content,
    )
}

/**
 * Scope required to attach [fluidGlassSource]. Making [fluidGlassSource] a member of this scope
 * guarantees at compile time that every descendant inside the recorded backdrop container has
 * [LocalInsideFluidGlassSource] == `true` and can never create a cyclic `RenderNode` reference on
 * Android's `RenderThread`.
 */
class FluidGlassSourceScope internal constructor(internal val host: FluidGlassHostState?) {
    fun Modifier.fluidGlassSource(): Modifier = composed {
        val h = host ?: return@composed this
        val sourceLayer = rememberGraphicsLayer()
        val recorded = remember(sourceLayer) { RecordedBackdropSource(sourceLayer) }
        SideEffect {
            if (h.activeSource !== recorded) {
                h.activeSource = recorded
            }
        }
        DisposableEffect(h, recorded) {
            onDispose {
                recorded.hasRecorded = false
                if (h.activeSource === recorded) {
                    h.activeSource = null
                    h.hasSource = false
                }
            }
        }
        this
            .onGloballyPositioned { coords ->
                h.sourceOffset = coords.positionInRoot()
                h.sourceSize = Size(coords.size.width.toFloat(), coords.size.height.toFloat())
                h.hasSource = coords.size.width > 0 && coords.size.height > 0
            }
            .drawWithContent {
                h.recordingDepth++
                try {
                    sourceLayer.record {
                        this@drawWithContent.drawContent()
                    }
                    recorded.hasRecorded = true
                } finally {
                    h.recordingDepth--
                }
                drawLayer(sourceLayer)
            }
    }
}

/**
 * Wraps the scrollable backdrop container whose pixels are sampled by floating [FluidGlassSurface]
 * siblings outside this block. Any [FluidGlassSurface] nested inside [content] renders crisp
 * standalone glass without sampling the ancestor backdrop layer.
 */
@Composable
fun FluidGlassSource(content: @Composable FluidGlassSourceScope.() -> Unit) {
    val inside = LocalInsideFluidGlassSource.current
    val host = if (inside) null else LocalFluidGlassHost.current
    val scope = remember(host) { FluidGlassSourceScope(host) }
    CompositionLocalProvider(LocalInsideFluidGlassSource provides true) {
        scope.content()
    }
}

private const val FLUID_GLASS_AGSL = """
    uniform shader content;
    uniform float2 uSize;
    uniform float uCorner;
    uniform float uBevel;
    uniform float uRefract;
    uniform float uDisperse;
    uniform float uLightMode;

    float sdRoundRect(float2 p, float2 b, float r) {
        float2 q = abs(p) - b + float2(r);
        return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
    }

    half4 main(float2 fragCoord) {
        if (uSize.x <= 2.0 || uSize.y <= 2.0) {
            return content.eval(fragCoord);
        }
        float2 halfSize = uSize * 0.5;
        float r = min(uCorner, min(halfSize.x, halfSize.y));
        float2 p = fragCoord - halfSize;
        float d = sdRoundRect(p, halfSize, r);

        float bevel = max(uBevel, 1.0);
        float t = clamp((d + bevel) / bevel, 0.0, 1.0);
        float lens = t * t * (3.0 - 2.0 * t);

        float2 q = abs(p) - (halfSize - float2(r));
        float2 signP = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
        float2 grad;
        if (q.x > 0.0 && q.y > 0.0) {
            float lenQ = max(length(q), 0.0001);
            grad = (q / lenQ) * signP;
        } else if (q.x > q.y) {
            grad = float2(signP.x, 0.0);
        } else {
            grad = float2(0.0, signP.y);
        }

        float2 baseShift = -grad * (lens * uRefract);
        float2 dispShift = grad * (lens * uDisperse * 0.42);

        float2 minBound = float2(0.5);
        float2 maxBound = max(uSize - float2(0.5), minBound);

        float2 uvR = clamp(fragCoord + baseShift - dispShift, minBound, maxBound);
        float2 uvG = clamp(fragCoord + baseShift, minBound, maxBound);
        float2 uvB = clamp(fragCoord + baseShift + dispShift, minBound, maxBound);

        half4 cR = content.eval(uvR);
        half4 cG = content.eval(uvG);
        half4 cB = content.eval(uvB);

        half3 rgb = half3(cR.r, cG.g, cB.b);
        half luma = dot(rgb, half3(0.2126, 0.7152, 0.0722));
        rgb = clamp(mix(half3(luma), rgb, half(1.22)), half3(0.0), half3(1.0));

        float edgeGlow = t * t * t;
        float vert = abs(grad.y);
        float horiz = abs(grad.x);
        if (uLightMode > 0.5) {
            // Anisotropic crystal glass in daylight: crisp bright specular along top/bottom,
            // thin dark refractive absorption on left/right lateral edges.
            float specTB = vert * edgeGlow * 0.28;
            float absorbLR = horiz * edgeGlow;
            rgb = rgb + half3(specTB) - half3(absorbLR * 0.11, absorbLR * 0.085, absorbLR * 0.05);
        } else {
            float specTB = vert * edgeGlow * 0.22;
            rgb = rgb + half3(specTB);
        }
        rgb = clamp(rgb, half3(0.0), half3(1.0));

        return half4(rgb, half(1.0));
    }
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun buildAgslRenderEffect(
    shader: RuntimeShader,
    widthPx: Float,
    heightPx: Float,
    cornerPx: Float,
    bevelPx: Float,
    refractPx: Float,
    dispersePx: Float,
    blurPx: Float,
    isLight: Boolean,
): androidx.compose.ui.graphics.RenderEffect? = runCatching {
    shader.setFloatUniform("uSize", widthPx.coerceAtLeast(2f), heightPx.coerceAtLeast(2f))
    shader.setFloatUniform("uCorner", cornerPx.coerceAtLeast(1f))
    shader.setFloatUniform("uBevel", bevelPx.coerceAtLeast(2f))
    shader.setFloatUniform("uRefract", refractPx)
    shader.setFloatUniform("uDisperse", dispersePx)
    shader.setFloatUniform("uLightMode", if (isLight) 1f else 0f)
    val shaderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
    val effect = if (blurPx > 0.25f) {
        val blurEffect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
        RenderEffect.createChainEffect(shaderEffect, blurEffect)
    } else {
        shaderEffect
    }
    effect.asComposeRenderEffect()
}.getOrNull()

@RequiresApi(Build.VERSION_CODES.S)
private fun buildBlurFallbackEffect(blurPx: Float): androidx.compose.ui.graphics.RenderEffect? = runCatching {
    val r = blurPx.coerceAtLeast(1f)
    RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP).asComposeRenderEffect()
}.getOrNull()

/**
 * Core optical-refraction Liquid Glass surface.
 *
 * - Android 13+ (API 33+): Real-time AGSL rounded-capsule SDF Snell refraction + RGB chromatic
 *   dispersion + 2dp micro clarity blur over the live [LocalFluidGlassHost] backdrop, keeping
 *   foreground icons and text 100% sharp in a separate unblurred child layer.
 * - Android 12 (API 31–32): Hardware `RenderEffect` micro-blur over the live backdrop + Fresnel rim.
 * - Android 8–11 (API 26–30): Ultra-clear translucent glass fill + directional Fresnel specular rim.
 */
@Composable
fun FluidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    isCapsule: Boolean = true,
    cornerRadius: Dp = 28.dp,
    tintColor: Color? = null,
    borderAccent: Color? = null,
    refraction: Dp = FluidGlassDefaults.Refraction,
    dispersion: Dp = FluidGlassDefaults.Dispersion,
    clarityBlur: Dp = FluidGlassDefaults.ClarityBlur,
    bevel: Dp = FluidGlassDefaults.MeniscusBevel,
    shadowElevation: Dp = 8.dp,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val insideSource = LocalInsideFluidGlassSource.current
    val host = if (insideSource) null else LocalFluidGlassHost.current
    val isLight = OpenChatPalette.isLight
    val screenBg = OpenChatPalette.Screen
    val density = LocalDensity.current

    var elemOffset by remember { mutableStateOf(Offset.Zero) }
    var elemSize by remember { mutableStateOf(Size.Zero) }

    val overlapsSource by remember(host) {
        derivedStateOf {
            val h = host
            val src = h?.activeSource
            h != null &&
                h.hasSource &&
                src != null &&
                elemSize.width > 2f &&
                elemSize.height > 2f &&
                elemOffset.x + elemSize.width > h.sourceOffset.x + 1f &&
                elemOffset.y + elemSize.height > h.sourceOffset.y + 1f &&
                elemOffset.x < h.sourceOffset.x + h.sourceSize.width - 1f &&
                elemOffset.y < h.sourceOffset.y + h.sourceSize.height - 1f
        }
    }

    val refractPx = with(density) { refraction.toPx() }
    val dispersePx = with(density) { dispersion.toPx() }
    val blurPx = with(density) { clarityBlur.toPx() }
    val bevelPx = with(density) { bevel.toPx() }
    val fixedCornerPx = with(density) { cornerRadius.toPx() }

    val runtimeShader = remember(insideSource) {
        if (!insideSource && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(FLUID_GLASS_AGSL) }.getOrNull()
        } else null
    }

    val cornerPx = if (isCapsule) {
        (minOf(elemSize.width, elemSize.height) * 0.5f).coerceAtLeast(1f)
    } else {
        fixedCornerPx
    }

    val composeEffect = remember(overlapsSource, runtimeShader, elemSize, cornerPx, bevelPx, refractPx, dispersePx, blurPx, isLight) {
        if (!overlapsSource || elemSize.width <= 2f || elemSize.height <= 2f) {
            null
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && runtimeShader != null) {
            buildAgslRenderEffect(runtimeShader, elemSize.width, elemSize.height, cornerPx, bevelPx, refractPx, dispersePx, blurPx, isLight)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            buildBlurFallbackEffect(blurPx * 2f)
        } else {
            null
        }
    }

    val refractingLive = overlapsSource && composeEffect != null
    val ambientShadow = if (isLight) Color(0x3B0F203A) else Color(0x6E000000)
    val spotShadow = if (isLight) Color(0x520F203A) else Color(0x80000000)

    Box(
        modifier = modifier
            .let {
                if (host != null) {
                    it.onGloballyPositioned { coords ->
                        elemOffset = coords.positionInRoot()
                        elemSize = Size(coords.size.width.toFloat(), coords.size.height.toFloat())
                    }
                } else it
            }
            .let {
                if (shadowElevation > 0.dp) {
                    it.shadow(
                        elevation = shadowElevation,
                        shape = shape,
                        ambientColor = ambientShadow,
                        spotColor = spotShadow,
                    )
                } else it
            }
            .clip(shape),
        contentAlignment = contentAlignment,
    ) {
        // Layer 1: Refracted & clarity-blurred live backdrop slice (strictly outside FluidGlassSource)
        if (host != null && refractingLive) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        clip = true
                        this.shape = shape
                        renderEffect = composeEffect
                    }
                    .drawBehind {
                        drawRect(screenBg)
                        val src = host.activeSource
                        if (host.recordingDepth == 0 && src != null && src.hasRecorded) {
                            val relX = elemOffset.x - host.sourceOffset.x
                            val relY = elemOffset.y - host.sourceOffset.y
                            translate(left = -relX, top = -relY) {
                                drawLayer(src.layer)
                            }
                        }
                    },
            )
        }

        // Layer 2: Zero-fog crystal glass body + anisotropic optical rim
        // (Light theme: thin dark refractive left/right edges + thin bright top/bottom specular edges)
        Box(
            Modifier
                .matchParentSize()
                .drawFluidGlassOpticalBody(
                    isLight = isLight,
                    isCapsule = isCapsule,
                    fixedCornerPx = fixedCornerPx,
                    refractingLive = refractingLive,
                    tintColor = tintColor,
                    borderAccent = borderAccent,
                ),
        )

        // Layer 3: Razor-sharp foreground content
        content()
    }
}

/**
 * Draws the zero-fog crystal interior, subtle bottom optical caustic, and physical anisotropic
 * glass rim:
 * - **Left & right flanks (`两边`)**: thin dark refractive glass edges so light-theme glass never
 *   melts into a daylight canvas.
 * - **Top & bottom rims (`上下`)**: thin bright specular catchlights backed by a micro-hairline
 *   outer separation contour.
 */
private fun Modifier.drawFluidGlassOpticalBody(
    isLight: Boolean,
    isCapsule: Boolean,
    fixedCornerPx: Float,
    refractingLive: Boolean,
    tintColor: Color?,
    borderAccent: Color?,
): Modifier = drawWithCache {
    val w = size.width.coerceAtLeast(2f)
    val h = size.height.coerceAtLeast(2f)
    val r = if (isCapsule) {
        minOf(w, h) * 0.5f
    } else {
        fixedCornerPx.coerceAtMost(minOf(w, h) * 0.5f)
    }
    val outerCorner = CornerRadius(r, r)

    // 1. Interior crystal fill + subtle bottom caustic glow (zero milky surface fog)
    val baseFillBrush: Brush = when {
        tintColor != null -> {
            val caustic = (borderAccent ?: tintColor).copy(alpha = if (isLight) 0.30f else 0.34f)
            Brush.verticalGradient(
                0.00f to tintColor,
                0.72f to tintColor,
                1.00f to lerp(tintColor, caustic, 0.48f),
            )
        }
        isLight && refractingLive -> {
            Brush.verticalGradient(
                0.00f to Color.White.copy(alpha = 0.12f),
                0.65f to Color.White.copy(alpha = 0.07f),
                1.00f to Color(0xFF2878FF).copy(alpha = 0.08f),
            )
        }
        isLight -> {
            Brush.verticalGradient(
                0.00f to Color.White.copy(alpha = 0.72f),
                0.55f to Color(0xFFF3F8FE).copy(alpha = 0.52f),
                1.00f to Color(0xFFDCE9F8).copy(alpha = 0.62f),
            )
        }
        refractingLive -> {
            Brush.verticalGradient(
                0.00f to Color(0xFF141C20).copy(alpha = 0.24f),
                0.70f to Color(0xFF0E1418).copy(alpha = 0.18f),
                1.00f to Color(0xFF68B2FF).copy(alpha = 0.10f),
            )
        }
        else -> {
            Brush.verticalGradient(
                0.00f to Color(0xFF1A2327).copy(alpha = 0.80f),
                1.00f to Color(0xFF12181B).copy(alpha = 0.82f),
            )
        }
    }

    // 2. Anisotropic perimeter rim parameterized by circular-arc cosine projection:
    //    - 0..30 deg on left/right curved flanks: thin dark refractive glass edge
    //    - 45 deg corner: smooth mid-tone transition
    //    - 65..115 deg across top & bottom rails: thin bright pure-white specular catchlight
    val capRatio = (r / w).coerceIn(0.05f, 0.50f)
    val darkHoldStop = (capRatio * 0.14f).coerceIn(0.012f, 0.08f)
    val diagStop = (capRatio * 0.30f).coerceIn(0.028f, 0.16f)
    val brightStop = (capRatio * 0.58f).coerceIn(0.055f, 0.30f)

    val rimStrokePx = 1.18.dp.toPx()
    val outerHairPx = 0.60.dp.toPx()
    val rimInset = rimStrokePx * 0.5f + (if (isLight) outerHairPx * 0.85f else 0f)
    val rimCorner = CornerRadius((r - rimInset).coerceAtLeast(1f), (r - rimInset).coerceAtLeast(1f))

    val rimBrush = if (isLight) {
        val darkPole = if (borderAccent != null) {
            lerp(borderAccent, Color(0xFF0D1B2C), 0.48f).copy(alpha = 0.68f)
        } else {
            Color(0xFF14263C).copy(alpha = 0.50f)
        }
        val midCap = if (borderAccent != null) {
            borderAccent.copy(alpha = 0.44f)
        } else {
            Color(0xFF435D7C).copy(alpha = 0.28f)
        }
        val brightTopBottom = if (borderAccent != null) {
            lerp(borderAccent, Color.White, 0.80f).copy(alpha = 1.0f)
        } else {
            Color.White
        }
        Brush.horizontalGradient(
            0.00f to darkPole,
            darkHoldStop to darkPole,
            diagStop to midCap,
            brightStop to brightTopBottom,
            (1f - brightStop) to brightTopBottom,
            (1f - diagStop) to midCap,
            (1f - darkHoldStop) to darkPole,
            1.00f to darkPole,
        )
    } else {
        val darkPole = if (borderAccent != null) {
            borderAccent.copy(alpha = 0.36f)
        } else {
            Color.White.copy(alpha = 0.14f)
        }
        val midCap = if (borderAccent != null) {
            borderAccent.copy(alpha = 0.52f)
        } else {
            Color(0xFF8CC4FF).copy(alpha = 0.22f)
        }
        val brightTopBottom = if (borderAccent != null) {
            lerp(borderAccent, Color.White, 0.58f).copy(alpha = 0.88f)
        } else {
            Color.White.copy(alpha = 0.62f)
        }
        Brush.horizontalGradient(
            0.00f to darkPole,
            darkHoldStop to darkPole,
            diagStop to midCap,
            brightStop to brightTopBottom,
            (1f - brightStop) to brightTopBottom,
            (1f - diagStop) to midCap,
            (1f - darkHoldStop) to darkPole,
            1.00f to darkPole,
        )
    }

    // 3. Crisp top & bottom inner specular sheen
    val innerSpecStrokePx = 0.85.dp.toPx()
    val innerInset = rimInset + 0.75.dp.toPx()
    val innerCorner = CornerRadius((r - innerInset).coerceAtLeast(1f), (r - innerInset).coerceAtLeast(1f))
    val innerSpecBrush = Brush.verticalGradient(
        0.00f to Color.White.copy(alpha = if (isLight) 0.95f else 0.40f),
        0.18f to Color.Transparent,
        0.82f to Color.Transparent,
        1.00f to Color.White.copy(alpha = if (isLight) 0.78f else 0.24f),
    )

    val outerHairColor = if (isLight) {
        (borderAccent?.let { lerp(it, Color(0xFF102036), 0.55f) } ?: Color(0xFF102036)).copy(alpha = 0.13f)
    } else {
        Color.Transparent
    }

    onDrawBehind {
        drawRoundRect(brush = baseFillBrush, cornerRadius = outerCorner)
        if (isLight && size.width > 4f && size.height > 4f) {
            val hi = outerHairPx * 0.5f
            drawRoundRect(
                color = outerHairColor,
                topLeft = Offset(hi, hi),
                size = Size(size.width - hi * 2f, size.height - hi * 2f),
                cornerRadius = outerCorner,
                style = Stroke(width = outerHairPx),
            )
        }
        if (size.width > rimInset * 2f + 2f && size.height > rimInset * 2f + 2f) {
            drawRoundRect(
                brush = rimBrush,
                topLeft = Offset(rimInset, rimInset),
                size = Size(size.width - rimInset * 2f, size.height - rimInset * 2f),
                cornerRadius = rimCorner,
                style = Stroke(width = rimStrokePx),
            )
        }
        if (size.width > innerInset * 2f + 2f && size.height > innerInset * 2f + 2f) {
            drawRoundRect(
                brush = innerSpecBrush,
                topLeft = Offset(innerInset, innerInset),
                size = Size(size.width - innerInset * 2f, size.height - innerInset * 2f),
                cornerRadius = innerCorner,
                style = Stroke(width = innerSpecStrokePx),
            )
        }
    }
}

/**
 * Ultra-clear active water-droplet indicator used inside [CapsuleNavigationBar] and segmented
 * selectors: lets underlying refracted pixels shine through with thin dark left/right edges and
 * thin bright top/bottom specular highlights.
 */
internal fun Modifier.fluidGlassDroplet(isLight: Boolean): Modifier = drawWithCache {
    val w = size.width.coerceAtLeast(2f)
    val h = size.height.coerceAtLeast(2f)
    val r = minOf(w, h) * 0.5f
    val corner = CornerRadius(r, r)
    val capRatio = (r / w).coerceIn(0.08f, 0.50f)
    val darkHoldStop = (capRatio * 0.14f).coerceIn(0.015f, 0.08f)
    val diagStop = (capRatio * 0.30f).coerceIn(0.032f, 0.16f)
    val brightStop = (capRatio * 0.58f).coerceIn(0.06f, 0.30f)
    val outerHairPx = 0.50.dp.toPx()
    val strokePx = 1.08.dp.toPx()
    val inset = strokePx * 0.5f + (if (isLight) outerHairPx * 0.75f else 0f)
    val innerCorner = CornerRadius((r - inset).coerceAtLeast(1f), (r - inset).coerceAtLeast(1f))

    val fillBrush = if (isLight) {
        Brush.verticalGradient(
            0.00f to Color.White.copy(alpha = 0.36f),
            0.58f to Color.White.copy(alpha = 0.20f),
            1.00f to Color(0xFF2476FF).copy(alpha = 0.10f),
        )
    } else {
        Brush.verticalGradient(
            0.00f to Color.White.copy(alpha = 0.14f),
            0.60f to Color.White.copy(alpha = 0.08f),
            1.00f to Color(0xFF87CDFF).copy(alpha = 0.13f),
        )
    }
    val rimBrush = if (isLight) {
        val darkPole = Color(0xFF15273D).copy(alpha = 0.42f)
        val midCap = Color(0xFF4C6584).copy(alpha = 0.24f)
        val brightTB = Color.White
        Brush.horizontalGradient(
            0.00f to darkPole,
            darkHoldStop to darkPole,
            diagStop to midCap,
            brightStop to brightTB,
            (1f - brightStop) to brightTB,
            (1f - diagStop) to midCap,
            (1f - darkHoldStop) to darkPole,
            1.00f to darkPole,
        )
    } else {
        val darkPole = Color.White.copy(alpha = 0.15f)
        val midCap = Color(0xFF87CDFF).copy(alpha = 0.24f)
        val brightTB = Color.White.copy(alpha = 0.60f)
        Brush.horizontalGradient(
            0.00f to darkPole,
            darkHoldStop to darkPole,
            diagStop to midCap,
            brightStop to brightTB,
            (1f - brightStop) to brightTB,
            (1f - diagStop) to midCap,
            (1f - darkHoldStop) to darkPole,
            1.00f to darkPole,
        )
    }
    onDrawBehind {
        drawRoundRect(brush = fillBrush, cornerRadius = corner)
        if (isLight && w > 4f && h > 4f) {
            val hi = outerHairPx * 0.5f
            drawRoundRect(
                color = Color(0xFF102036).copy(alpha = 0.10f),
                topLeft = Offset(hi, hi),
                size = Size(w - hi * 2f, h - hi * 2f),
                cornerRadius = corner,
                style = Stroke(width = outerHairPx),
            )
        }
        if (w > inset * 2f + 2f && h > inset * 2f + 2f) {
            drawRoundRect(
                brush = rimBrush,
                topLeft = Offset(inset, inset),
                size = Size(w - inset * 2f, h - inset * 2f),
                cornerRadius = innerCorner,
                style = Stroke(width = strokePx),
            )
        }
    }
}

/** 36dp circular Liquid Glass back button inside a comfortable 48dp header touch target. */
@Composable
internal fun GlassBackButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        FluidGlassCircleButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.back),
            onClick = onClick,
            size = 36.dp,
            iconSize = 18.dp,
            shadowElevation = 5.dp,
        )
    }
}

/** Stadium-shaped Liquid Glass capsule used for floating bars, inputs, and status pills. */
@Composable
fun FluidGlassCapsule(
    modifier: Modifier = Modifier,
    tintColor: Color? = null,
    borderAccent: Color? = null,
    shadowElevation: Dp = 10.dp,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    FluidGlassSurface(
        modifier = modifier,
        shape = CircleShape,
        isCapsule = true,
        tintColor = tintColor,
        borderAccent = borderAccent,
        shadowElevation = shadowElevation,
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * Clean circular Liquid Glass button with pure Fresnel perimeter rim (zero drawn crescent arcs
 * inside the button face, per design specification) and tactile spring press bounce.
 */
@Composable
fun FluidGlassCircleButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    iconTint: Color = OpenChatPalette.Ink,
    tintColor: Color? = null,
    borderAccent: Color? = null,
    enabled: Boolean = true,
    shadowElevation: Dp = 8.dp,
) {
    FluidGlassSurface(
        modifier = modifier
            .size(size)
            .let {
                if (enabled && onClick != null) {
                    it.pressScale(0.92f).clickable(role = Role.Button, onClick = onClick)
                } else it
            },
        shape = CircleShape,
        isCapsule = true,
        tintColor = tintColor,
        borderAccent = borderAccent,
        shadowElevation = shadowElevation,
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) iconTint else iconTint.copy(alpha = 0.42f),
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Compact clickable Liquid Glass pill button for header actions and inline CTAs. */
@Composable
fun FluidGlassPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tintColor: Color? = null,
    borderAccent: Color? = null,
    shadowElevation: Dp = 4.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
    content: @Composable RowScope.() -> Unit,
) {
    FluidGlassSurface(
        modifier = modifier
            .let { if (enabled) it.pressScale(0.95f) else it }
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = CircleShape,
        isCapsule = true,
        tintColor = tintColor,
        borderAccent = borderAccent,
        shadowElevation = shadowElevation,
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}
