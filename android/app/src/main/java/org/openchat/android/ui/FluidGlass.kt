package org.openchat.android.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openchat.android.R

/**
 * Optical parameters for OpenChat's in-tree Liquid Glass engine:
 * crisp curved-meniscus Snell refraction, subtle RGB chromatic dispersion,
 * silky frosted optical blur, and luminous refractive interiors.
 */
object FluidGlassDefaults {
    val Refraction: Dp = 12.5.dp
    val Dispersion: Dp = 2.0.dp
    val ClarityBlur: Dp = 4.2.dp
    val MeniscusBevel: Dp = 15.dp
    val TopProgressiveMaxBlur: Dp = 19.dp
}

/**
 * Normalized progressive top-blur weight across vertical position `v` (`0f` = screen top edge,
 * `1f` = bottom of the feather zone right above the active dialogue viewport):
 * - `v = 0.00f`: `1.0f` (maximum blur at status bar top)
 * - `v = 0.65f`: `~0.61f` (remains softly blurred directly behind the header title & action capsule)
 * - `v >= 0.98f`: `0.0f` (completely sharp and clear in the chat conversation area below)
 */
internal fun progressiveBlurWeight(v: Float): Float {
    val clamped = v.coerceIn(0f, 1f)
    if (clamped <= 0.36f) return 1f
    if (clamped >= 0.98f) return 0f
    val t = ((clamped - 0.36f) / (0.98f - 0.36f)).coerceIn(0f, 1f)
    val smooth = t * t * (3f - 2f * t)
    return 1f - smooth
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

        // Crease-free C1-continuous 2D boundary gradient
        float2 q = abs(p) - (halfSize - float2(r));
        float2 signP = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
        float2 outsideQ = max(q, float2(0.0));
        float outLen = length(outsideQ);
        float2 grad;
        if (outLen > 0.0001) {
            grad = (outsideQ / outLen) * signP;
        } else {
            float2 w = exp(clamp(q * 0.35, float2(-12.0), float2(0.0)));
            grad = normalize(w + float2(0.0001)) * signP;
        }

        // 3D curved pillow meniscus normal N = (nx, ny, nz)
        float sinTheta = pow(t, 1.55) * 0.88;
        float cosTheta = sqrt(max(1.0 - sinTheta * sinTheta, 0.03));
        float3 normal = float3(grad.x * sinTheta, grad.y * sinTheta, cosTheta);

        // Snell lens refraction + subtle chromatic dispersion at grazing bevel
        float lens = t * t * (3.0 - 2.0 * t);
        float2 baseShift = -normal.xy * (uRefract * (0.35 + 0.65 * lens));
        float2 dispShift = normal.xy * (lens * uDisperse * 0.45);

        float2 minBound = float2(0.5);
        float2 maxBound = max(uSize - float2(0.5), minBound);

        float2 uvR = clamp(fragCoord + baseShift - dispShift, minBound, maxBound);
        float2 uvG = clamp(fragCoord + baseShift, minBound, maxBound);
        float2 uvB = clamp(fragCoord + baseShift + dispShift, minBound, maxBound);

        half4 cR = content.eval(uvR);
        half4 cG = content.eval(uvG);
        half4 cB = content.eval(uvB);

        // iOS 27 optical vibrancy boost (compensates Gaussian desaturation so colors stay crisp)
        half3 rgb = half3(cR.r, cG.g, cB.b);
        half luma = dot(rgb, half3(0.2126, 0.7152, 0.0722));
        half satBoost = uLightMode > 0.5 ? half(1.44) : half(1.48);
        rgb = clamp(mix(half3(luma), rgb, satBoost), half3(0.0), half3(1.0));

        // Schlick's Fresnel reflection + 135-deg top-left primary glint + bottom-right counter glint
        float fresnel = pow(clamp(1.0 - normal.z, 0.0, 1.0), 3.6);
        float3 halfVecPrimary = normalize(float3(-0.38, -0.68, 1.18));
        float3 halfVecCounter = normalize(float3(0.36, 0.64, 1.22));
        float specPrimary = pow(max(dot(normal, halfVecPrimary), 0.0), 18.0) * lens;
        float specCounter = pow(max(dot(normal, halfVecCounter), 0.0), 14.0) * lens;
        float horiz = abs(grad.x);

        if (uLightMode > 0.5) {
            float glint = specPrimary * 0.38 + specCounter * 0.18 + fresnel * 0.22;
            float absorbLR = horiz * fresnel * 0.14;
            rgb = rgb + half3(glint) - half3(absorbLR, absorbLR * 0.88, absorbLR * 0.72);
        } else {
            float glint = specPrimary * 0.30 + specCounter * 0.14 + fresnel * 0.16;
            rgb = rgb + half3(glint);
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
 * Wraps a `Scaffold` `topBar` with a non-interactive, grain-free 3-octave hardware Gaussian
 * progressive blur veil (`22dp -> 13dp -> 5.5dp -> 0dp`):
 * - Maximum continuous Gaussian blur at the very top edge (`y = 0`),
 * - Remains softly blurred directly behind the header title, subtitle, and top-right Liquid Glass
 *   action capsule,
 * - Smoothly feathers to `0` blur and `0` opacity over [feather] below the bar without inflating
 *   the bar's measured layout height, keeping the entire dialogue area below 100% razor-sharp.
 */
@Composable
fun ProgressiveBlurTopBar(
    modifier: Modifier = Modifier,
    feather: Dp = 18.dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val featherPx = with(density) { feather.roundToPx() }.coerceAtLeast(0)
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            TopProgressiveBlurSurface()
            Box(Modifier.fillMaxWidth()) { content() }
        },
    ) { measurables, constraints ->
        val fgPlaceable = measurables[1].measure(constraints.copy(minHeight = 0))
        val blurHeight = (fgPlaceable.height + featherPx).coerceAtLeast(1)
        val blurPlaceable = measurables[0].measure(Constraints.fixed(fgPlaceable.width.coerceAtLeast(1), blurHeight))
        layout(fgPlaceable.width, fgPlaceable.height) {
            blurPlaceable.place(0, 0)
            fgPlaceable.place(0, 0)
        }
    }
}

@Composable
private fun GaussianBlurOctave(
    host: FluidGlassHostState,
    elemOffset: Offset,
    veilColor: Color,
    blurEffect: androidx.compose.ui.graphics.RenderEffect,
    maskBrush: Brush,
) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(brush = maskBrush, blendMode = BlendMode.DstIn)
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    clip = true
                    renderEffect = blurEffect
                }
                .drawBehind {
                    drawRect(veilColor)
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
}

@Composable
private fun TopProgressiveBlurSurface() {
    val insideSource = LocalInsideFluidGlassSource.current
    val host = if (insideSource) null else LocalFluidGlassHost.current
    val isLight = OpenChatPalette.isLight
    val veilColor = OpenChatPalette.Screen
    val density = LocalDensity.current

    val softBlurPx = with(density) { 5.5.dp.toPx() }
    val midBlurPx = with(density) { 13.dp.toPx() }
    val deepBlurPx = with(density) { 22.dp.toPx() }

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

    val blurOctaves = remember(overlapsSource, softBlurPx, midBlurPx, deepBlurPx) {
        if (overlapsSource && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soft = buildBlurFallbackEffect(softBlurPx)
            val mid = buildBlurFallbackEffect(midBlurPx)
            val deep = buildBlurFallbackEffect(deepBlurPx)
            if (soft != null && mid != null && deep != null) Triple(soft, mid, deep) else null
        } else null
    }

    val softMask = remember {
        Brush.verticalGradient(
            0.00f to Color.Black,
            0.60f to Color.Black,
            0.84f to Color.Black.copy(alpha = 0.50f),
            1.00f to Color.Transparent,
        )
    }
    val midMask = remember {
        Brush.verticalGradient(
            0.00f to Color.Black,
            0.44f to Color.Black,
            0.70f to Color.Black.copy(alpha = 0.78f),
            0.91f to Color.Transparent,
        )
    }
    val deepMask = remember {
        Brush.verticalGradient(
            0.00f to Color.Black,
            0.25f to Color.Black.copy(alpha = 0.92f),
            0.60f to Color.Transparent,
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .let {
                if (host != null) {
                    it.onGloballyPositioned { coords ->
                        elemOffset = coords.positionInRoot()
                        elemSize = Size(coords.size.width.toFloat(), coords.size.height.toFloat())
                    }
                } else it
            },
    ) {
        if (host != null && overlapsSource && blurOctaves != null) {
            val (softEffect, midEffect, deepEffect) = blurOctaves
            // 3-tier separable hardware Gaussian stack: silky continuous blur with zero grain
            GaussianBlurOctave(host, elemOffset, veilColor, softEffect, softMask)
            GaussianBlurOctave(host, elemOffset, veilColor, midEffect, midMask)
            GaussianBlurOctave(host, elemOffset, veilColor, deepEffect, deepMask)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to veilColor.copy(alpha = if (isLight) 0.78f else 0.74f),
                            0.56f to veilColor.copy(alpha = if (isLight) 0.42f else 0.40f),
                            0.82f to veilColor.copy(alpha = if (isLight) 0.14f else 0.12f),
                            1.00f to Color.Transparent,
                        ),
                    ),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to veilColor.copy(alpha = if (isLight) 0.95f else 0.92f),
                            0.62f to veilColor.copy(alpha = if (isLight) 0.78f else 0.74f),
                            0.85f to veilColor.copy(alpha = if (isLight) 0.34f else 0.30f),
                            1.00f to Color.Transparent,
                        ),
                    ),
            )
        }
    }
}

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
            buildBlurFallbackEffect(blurPx * 1.35f)
        } else {
            null
        }
    }

    val refractingLive = overlapsSource && composeEffect != null
    val ambientShadow = if (isLight) Color(0x1E111318) else Color(0x6E000000)
    val spotShadow = if (isLight) Color(0x2E111318) else Color(0x80000000)

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

        // Layer 3: Razor-sharp foreground content with theme-aware default ink
        CompositionLocalProvider(LocalContentColor provides OpenChatPalette.Ink) {
            content()
        }
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

    // 1. Interior crystal fill + directional top-left sheen & bottom caustic (low-fog iOS 27 clarity)
    val baseFillBrush: Brush = when {
        tintColor != null -> {
            val caustic = (borderAccent ?: tintColor).copy(alpha = if (isLight) 0.34f else 0.38f)
            Brush.linearGradient(
                0.00f to lerp(tintColor, Color.White, if (isLight) 0.22f else 0.14f),
                0.62f to tintColor,
                1.00f to lerp(tintColor, caustic, 0.48f),
                start = Offset.Zero,
                end = Offset(w * 0.45f, h),
            )
        }
        isLight && refractingLive -> {
            Brush.linearGradient(
                0.00f to Color.White.copy(alpha = 0.62f),
                0.48f to Color.White.copy(alpha = 0.34f),
                1.00f to Color.White.copy(alpha = 0.46f),
                start = Offset(w * 0.12f, 0f),
                end = Offset(w * 0.88f, h),
            )
        }
        isLight -> {
            Brush.verticalGradient(
                0.00f to Color(0xFFFCFCFD).copy(alpha = 0.94f),
                0.55f to Color(0xFFF6F7F9).copy(alpha = 0.88f),
                1.00f to Color(0xFFF0F1F4).copy(alpha = 0.92f),
            )
        }
        refractingLive -> {
            Brush.linearGradient(
                0.00f to Color.White.copy(alpha = 0.13f),
                0.48f to Color(0xFF141519).copy(alpha = 0.30f),
                1.00f to Color.White.copy(alpha = 0.07f),
                start = Offset(w * 0.12f, 0f),
                end = Offset(w * 0.88f, h),
            )
        }
        else -> {
            Brush.verticalGradient(
                0.00f to Color(0xFF1E2025).copy(alpha = 0.86f),
                1.00f to Color(0xFF121316).copy(alpha = 0.88f),
            )
        }
    }

    // 2. iOS 27 high-contrast dual-pole specular rim + lateral refractive flanks:
    //    - Top-left & top rail: crisp pure-white specular lip
    //    - Left & right curved flanks: subtle dark refractive edge definition
    //    - Bottom-right & bottom rail: secondary caustic bounce highlight
    val capRatio = (r / w).coerceIn(0.05f, 0.50f)
    val darkHoldStop = (capRatio * 0.14f).coerceIn(0.012f, 0.08f)
    val diagStop = (capRatio * 0.30f).coerceIn(0.028f, 0.16f)
    val brightStop = (capRatio * 0.56f).coerceIn(0.055f, 0.30f)

    val rimStrokePx = 1.15.dp.toPx()
    val outerHairPx = 0.60.dp.toPx()
    val rimInset = rimStrokePx * 0.5f + outerHairPx * 0.65f
    val rimCorner = CornerRadius((r - rimInset).coerceAtLeast(1f), (r - rimInset).coerceAtLeast(1f))

    val rimBrush = if (isLight) {
        val darkPole = if (borderAccent != null) {
            lerp(borderAccent, Color(0xFF12151C), 0.45f).copy(alpha = 0.24f)
        } else {
            Color(0xFF12151C).copy(alpha = 0.14f)
        }
        val midCap = if (borderAccent != null) {
            borderAccent.copy(alpha = 0.22f)
        } else {
            Color.White.copy(alpha = 0.48f)
        }
        val brightTopBottom = if (borderAccent != null) {
            lerp(borderAccent, Color.White, 0.82f).copy(alpha = 0.80f)
        } else {
            Color.White.copy(alpha = 0.96f)
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
            borderAccent.copy(alpha = 0.22f)
        } else {
            Color.White.copy(alpha = 0.11f)
        }
        val midCap = if (borderAccent != null) {
            borderAccent.copy(alpha = 0.28f)
        } else {
            Color.White.copy(alpha = 0.20f)
        }
        val brightTopBottom = if (borderAccent != null) {
            lerp(borderAccent, Color.White, 0.65f).copy(alpha = 0.52f)
        } else {
            Color.White.copy(alpha = 0.46f)
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

    // 3. Diagonal 135-deg specular lip + bottom caustic rim
    val innerSpecStrokePx = 0.85.dp.toPx()
    val innerInset = rimInset + 0.65.dp.toPx()
    val innerCorner = CornerRadius((r - innerInset).coerceAtLeast(1f), (r - innerInset).coerceAtLeast(1f))
    val innerSpecBrush = Brush.linearGradient(
        0.00f to Color.White.copy(alpha = if (isLight) 0.82f else 0.36f),
        0.36f to Color.Transparent,
        0.70f to Color.Transparent,
        1.00f to Color.White.copy(alpha = if (isLight) 0.46f else 0.18f),
        start = Offset(w * 0.08f, 0f),
        end = Offset(w * 0.92f, h),
    )

    val outerHairColor = if (isLight) {
        (borderAccent?.let { lerp(it, Color(0xFF12151C), 0.55f) } ?: Color(0xFF12151C)).copy(alpha = 0.09f)
    } else {
        (borderAccent ?: Color.White).copy(alpha = 0.13f)
    }

    onDrawBehind {
        drawRoundRect(brush = baseFillBrush, cornerRadius = outerCorner)
        if (size.width > 4f && size.height > 4f) {
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
 * Apple Music iOS 26 style whisper-light translucent neutral-gray active indicator used inside
 * [CapsuleNavigationBar]: a very pale satin-gray pillow (~#E8E8EA over light glass, subtle
 * translucent graphite in OLED dark) with a delicate specular rim so the active tab is gently
 * distinct without looking stark white or heavy gray.
 */
internal fun Modifier.fluidGlassDroplet(isLight: Boolean): Modifier = drawWithCache {
    val w = size.width.coerceAtLeast(2f)
    val h = size.height.coerceAtLeast(2f)
    val r = minOf(w, h) * 0.5f
    val corner = CornerRadius(r, r)
    val rimPx = 0.75.dp.toPx()
    val inset = rimPx * 0.5f
    val innerCorner = CornerRadius((r - inset).coerceAtLeast(1f), (r - inset).coerceAtLeast(1f))

    val fillBrush = if (isLight) {
        Brush.linearGradient(
            0.00f to Color(0xFF1C1D21).copy(alpha = 0.050f),
            0.50f to Color(0xFF1C1D21).copy(alpha = 0.072f),
            1.00f to Color(0xFF1C1D21).copy(alpha = 0.060f),
            start = Offset(w * 0.15f, 0f),
            end = Offset(w * 0.85f, h),
        )
    } else {
        Brush.linearGradient(
            0.00f to Color(0xFFC8CDD6).copy(alpha = 0.13f),
            0.52f to Color(0xFFC8CDD6).copy(alpha = 0.095f),
            1.00f to Color(0xFFC8CDD6).copy(alpha = 0.11f),
            start = Offset(w * 0.15f, 0f),
            end = Offset(w * 0.85f, h),
        )
    }
    val rimBrush = if (isLight) {
        Brush.linearGradient(
            0.00f to Color.White.copy(alpha = 0.52f),
            0.48f to Color(0xFF12151C).copy(alpha = 0.035f),
            1.00f to Color.White.copy(alpha = 0.30f),
            start = Offset(w * 0.10f, 0f),
            end = Offset(w * 0.90f, h),
        )
    } else {
        Brush.linearGradient(
            0.00f to Color.White.copy(alpha = 0.22f),
            0.50f to Color.White.copy(alpha = 0.05f),
            1.00f to Color.White.copy(alpha = 0.12f),
            start = Offset(w * 0.10f, 0f),
            end = Offset(w * 0.90f, h),
        )
    }
    onDrawBehind {
        drawRoundRect(brush = fillBrush, cornerRadius = corner)
        if (w > inset * 2f + 2f && h > inset * 2f + 2f) {
            drawRoundRect(
                brush = rimBrush,
                topLeft = Offset(inset, inset),
                size = Size(w - inset * 2f, h - inset * 2f),
                cornerRadius = innerCorner,
                style = Stroke(width = rimPx),
            )
        }
    }
}

/** Pure borderless ghost back icon inside a comfortable 48dp header touch target. */
@Composable
internal fun GlassBackButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .pressScale(0.90f)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, radius = 20.dp),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.back),
            tint = OpenChatPalette.Ink,
            modifier = Modifier.size(21.dp),
        )
    }
}

/**
 * Stadium-shaped Liquid Glass capsule used by the bottom navigation bar ([CapsuleNavigationBar])
 * and top-right header action clusters ([GlassHeaderActionCluster]).
 */
@Composable
fun FluidGlassCapsule(
    modifier: Modifier = Modifier,
    tintColor: Color? = null,
    borderAccent: Color? = null,
    clarityBlur: Dp = FluidGlassDefaults.ClarityBlur,
    shadowElevation: Dp = 8.dp,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    FluidGlassSurface(
        modifier = modifier,
        shape = CircleShape,
        isCapsule = true,
        tintColor = tintColor,
        borderAccent = borderAccent,
        clarityBlur = clarityBlur,
        shadowElevation = shadowElevation,
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** Single action descriptor inside a top-right [GlassHeaderActionCluster]. */
data class GlassActionItem(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
    val active: Boolean = false,
    val activeTint: Color? = null,
    val badgeDot: Boolean = false,
    val iconTint: Color? = null,
)

/**
 * Top-right Liquid Glass header capsule sharing the exact optical refraction, frosted clarity blur,
 * anisotropic glass rim, and water-droplet active state as the bottom [CapsuleNavigationBar].
 */
@Composable
fun GlassHeaderActionCluster(
    items: List<GlassActionItem>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val isLight = OpenChatPalette.isLight
    val ink = OpenChatPalette.Ink
    val signal = OpenChatPalette.Signal
    if (items.size == 1) {
        val item = items[0]
        FluidGlassCapsule(
            modifier = modifier
                .size(36.dp)
                .pressScale(0.93f),
            tintColor = if (item.active) (item.activeTint ?: signal) else null,
            borderAccent = if (item.active) (item.activeTint ?: signal) else null,
            shadowElevation = 5.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = item.onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.contentDescription,
                    tint = when {
                        item.active -> Color.White
                        item.iconTint != null -> item.iconTint
                        else -> ink
                    },
                    modifier = Modifier.size(17.dp),
                )
                if (item.badgeDot) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 7.dp, end = 7.dp)
                            .size(6.5.dp)
                            .background(signal, CircleShape),
                    )
                }
            }
        }
        return
    }

    FluidGlassCapsule(
        modifier = modifier.height(36.dp),
        shadowElevation = 5.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { idx, item ->
                if (idx > 0) {
                    Box(
                        Modifier
                            .padding(horizontal = 1.dp)
                            .width(0.75.dp)
                            .height(14.dp)
                            .background(ink.copy(alpha = if (isLight) 0.11f else 0.14f)),
                    )
                }
                val accent = item.activeTint ?: signal
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 30.dp)
                        .pressScale(0.93f)
                        .clip(CircleShape)
                        .let { m ->
                            if (item.active) {
                                m.drawWithCache {
                                    val r = minOf(size.width, size.height) * 0.5f
                                    val c = CornerRadius(r, r)
                                    val stroke = 0.85.dp.toPx()
                                    val fill = Brush.linearGradient(
                                        0.00f to lerp(accent, Color.White, 0.22f),
                                        0.60f to accent,
                                        1.00f to lerp(accent, Color.Black, 0.10f),
                                        start = Offset.Zero,
                                        end = Offset(size.width, size.height),
                                    )
                                    val spec = Brush.linearGradient(
                                        0.00f to Color.White.copy(alpha = 0.65f),
                                        0.50f to Color.Transparent,
                                        1.00f to Color.White.copy(alpha = 0.25f),
                                        start = Offset.Zero,
                                        end = Offset(size.width, size.height),
                                    )
                                    onDrawBehind {
                                        drawRoundRect(brush = fill, cornerRadius = c)
                                        drawRoundRect(
                                            brush = spec,
                                            topLeft = Offset(stroke * 0.5f, stroke * 0.5f),
                                            size = Size(size.width - stroke, size.height - stroke),
                                            cornerRadius = c,
                                            style = Stroke(width = stroke),
                                        )
                                    }
                                }
                            } else m
                        }
                        .clickable(role = Role.Button, onClick = item.onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.contentDescription,
                        tint = when {
                            item.active -> Color.White
                            item.iconTint != null -> item.iconTint
                            else -> ink
                        },
                        modifier = Modifier.size(16.dp),
                    )
                    if (item.badgeDot && !item.active) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 4.dp, end = 5.dp)
                                .size(6.5.dp)
                                .background(signal, CircleShape),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Borderless tonal stadium capsule used for status bands, composer inputs, and voice bars.
 * Draws a stroke only when [borderColor] is explicitly provided (e.g. an active focus ring).
 */
@Composable
fun FlatCapsule(
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    borderColor: Color? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val fill = containerColor ?: OpenChatPalette.Control
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = fill,
        contentColor = OpenChatPalette.Ink,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = borderColor?.let { BorderStroke(1.dp, it) },
    ) {
        Box(contentAlignment = contentAlignment, content = content)
    }
}

/**
 * Borderless tonal circular button (zero drop-shadow, zero wireframe border) with tactile
 * spring press bounce.
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
    @Suppress("UNUSED_PARAMETER") borderAccent: Color? = null,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") shadowElevation: Dp = 0.dp,
) {
    val fill = tintColor ?: OpenChatPalette.Control
    Surface(
        modifier = modifier
            .size(size)
            .let {
                if (enabled && onClick != null) {
                    it.pressScale(0.93f).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
                } else it
            },
        shape = CircleShape,
        color = fill,
        contentColor = OpenChatPalette.Ink,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) iconTint else iconTint.copy(alpha = 0.42f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** Borderless tonal pill button for header actions and inline CTAs. */
@Composable
fun FluidGlassPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tintColor: Color? = null,
    @Suppress("UNUSED_PARAMETER") borderAccent: Color? = null,
    @Suppress("UNUSED_PARAMETER") shadowElevation: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val fill = tintColor ?: OpenChatPalette.Control
    Surface(
        modifier = modifier
            .let { if (enabled) it.pressScale(0.95f) else it }
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = CircleShape,
        color = fill,
        contentColor = OpenChatPalette.Ink,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

/**
 * 12dp rounded-rectangle borderless tonal button used inside cards and bottom sheets, replacing
 * wireframe `OutlinedButton`s across settings, verification, updates, and moderation actions.
 */
@Composable
internal fun TonalActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val isLight = OpenChatPalette.isLight
    val container = when {
        destructive && isLight -> Color(0xFFFEE4E2)
        destructive -> Color(0xFF331512)
        isLight -> OpenChatPalette.Card
        else -> OpenChatPalette.CardRaised
    }
    val fg = if (destructive) {
        if (isLight) Color(0xFFD92D20) else Color(0xFFFF8970)
    } else {
        OpenChatPalette.Ink
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = fg,
            disabledContainerColor = container.copy(alpha = 0.55f),
            disabledContentColor = fg.copy(alpha = 0.45f),
        ),
        contentPadding = contentPadding,
        modifier = modifier.defaultMinSize(minHeight = 44.dp),
        content = content,
    )
}
