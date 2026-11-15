package org.openchat.android.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
        rgb = clamp(mix(half3(luma), rgb, half(1.18)), half3(0.0), half3(1.0));

        float lightDir = dot(grad, float2(-0.62, -0.78));
        float catchLight = max(lightDir, 0.0) * (t * t * t) * (uLightMode > 0.5 ? 0.34 : 0.20);
        rgb = clamp(rgb + half3(catchLight), half3(0.0), half3(1.0));

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

    // Ultra-clear interior tint (zero milky fog)
    val fillTint = tintColor ?: if (isLight) {
        if (overlapsSource && composeEffect != null) Color.White.copy(alpha = 0.22f)
        else Color.White.copy(alpha = 0.84f)
    } else {
        if (overlapsSource && composeEffect != null) Color(0xFF141B1D).copy(alpha = 0.34f)
        else Color(0xFF151C1E).copy(alpha = 0.86f)
    }

    // Crisp directional Fresnel specular border (top-left primary catchlight + bottom-right rim)
    val rimBrush = remember(isLight, borderAccent) {
        if (borderAccent != null) {
            Brush.linearGradient(
                colors = listOf(
                    borderAccent.copy(alpha = if (isLight) 0.85f else 0.78f),
                    borderAccent.copy(alpha = 0.28f),
                    borderAccent.copy(alpha = if (isLight) 0.55f else 0.48f),
                ),
            )
        } else if (isLight) {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.96f),
                    Color(0xFFC8D4E6).copy(alpha = 0.52f),
                    Color.White.copy(alpha = 0.82f),
                ),
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.36f),
                    Color.White.copy(alpha = 0.09f),
                    Color(0xFF93BCE8).copy(alpha = 0.22f),
                ),
            )
        }
    }

    val ambientShadow = if (isLight) Color(0x220E1829) else Color(0x66000000)

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
                        spotColor = ambientShadow,
                    )
                } else it
            }
            .clip(shape),
        contentAlignment = contentAlignment,
    ) {
        // Layer 1: Refracted & clarity-blurred live backdrop slice (strictly outside FluidGlassSource)
        if (host != null && overlapsSource && composeEffect != null) {
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

        // Layer 2: Ultra-clear glass body + crisp Fresnel rim (no interior fog, no artificial arcs)
        Box(
            Modifier
                .matchParentSize()
                .background(fillTint, shape)
                .border(BorderStroke(1.2.dp, rimBrush), shape),
        )

        // Layer 3: Razor-sharp foreground content
        content()
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
