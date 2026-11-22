//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.roleswitch.android.R
import ai.roleswitch.android.service.CubismBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

/**
 * Character colour customization (character picker's "Customize" sheet).
 *
 * The bundled models each pack every part into one texture atlas (per model texture,
 * e.g. haru has three), so a "region" is not a separate image: it is a set of pixel
 * rectangles inside an atlas plus colour gates. The gates matter because a rectangle
 * cannot follow a part's outline — hibiki's dark-red hair and hiyori's brown hair both
 * live in the same hue band as the skin interleaved around them. Each region therefore
 * matches only pixels that are inside one of its rects *and* pass its saturation /
 * value / hue gates (grays keep their neutrality, white shirts stay white, and the
 * hair regions are kept from swallowing adjacent bright skin by a value ceiling —
 * skin is not a customizable region and must survive every shift untouched). Regions
 * are evaluated **first-match-wins** in table order, which is what makes overlapping
 * rects safe.
 *
 * The tables below were derived by segmenting the shipped atlases (connected
 * components per colour family); every rect was validated against the real pixels.
 * If a model is ever replaced or a new one added, re-derive its entry — the numbers
 * are pixel coordinates in that model's own atlases and transfer to nothing else.
 */

/** One pixel rectangle of a region: [x, y, w, h] inside texture [textureIndex]. */
internal data class RegionRect(val textureIndex: Int, val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * A recolorable area of a character.
 *
 * @param key stable id, persisted as the hue-map key (`hair`, `skin`, …)
 * @param labelRes localized row label in the sheet
 * @param satMin minimum saturation for a pixel to belong (0..1); grays below it are
 * left alone by every hue shift
 * @param satMax maximum saturation (0..1). Where skin and fabric share a hue window
 * (hiyori's sleeves sit over her arms at hue ≈35–42°) the skin's higher saturation
 * is the only separator — a hue-only gate there stains the limbs' shading.
 * @param valMin minimum value/brightness (0..1)
 * @param valMax maximum value/brightness (0..1); hibiki/hiyori use it to keep their
 * dark hair regions from swallowing adjacent bright skin
 * @param hueMin hue window start in degrees
 * @param hueMax hue window end in degrees; `hueMin > hueMax` means the window wraps
 * through red (hibiki's hair lives either side of 0°)
 */
internal data class Region(
    val key: String,
    @StringRes val labelRes: Int,
    val rects: List<RegionRect>,
    val satMin: Float,
    val satMax: Float = 1f,
    val valMin: Float = 0f,
    val valMax: Float = 1f,
    val hueMin: Float = 0f,
    val hueMax: Float = 360f
)

/** Texture atlas paths (model3.json `Textures` order) + regions for one character. */
private data class CharacterRegions(
    val textures: List<String>,
    val regions: List<Region>
)

private val hibikiRegions = CharacterRegions(
    textures = listOf("hibiki/hibiki.2048/texture_00.png"),
    regions = listOf(
        // Dark-red hair across the right half and the bottom strands, plus the
        // top-left tuft and the strand below the face. valMax 0.87 admits the
        // bright red highlight strands (v≈0.85) that a 0.75 ceiling used to skip —
        // shifted hair looked half-done — while still keeping out the v≈0.94 skin
        // piece at the quadrant's bottom and the near-white cream line art (v≈1.0).
        Region(
            key = "hair", labelRes = R.string.live2d_region_hair,
            rects = listOf(
                RegionRect(0, 1024, 0, 1024, 1024),
                RegionRect(0, 0, 1472, 700, 576),
                RegionRect(0, 0, 0, 130, 130),
                RegionRect(0, 0, 1020, 390, 70)
            ),
            satMin = 0.18f, valMax = 0.87f, hueMin = 330f, hueMax = 30f
        ),
        // Blue-gray blazer/sleeve pieces scattered down the left edge.
        Region(
            key = "clothes", labelRes = R.string.live2d_region_clothes,
            rects = listOf(RegionRect(0, 0, 0, 340, 1024)),
            satMin = 0.10f, hueMin = 160f, hueMax = 260f
        ),
        // The big blue-gray skirt mass, centre-left below the face.
        Region(
            key = "skirt", labelRes = R.string.live2d_region_skirt,
            rects = listOf(RegionRect(0, 440, 1000, 620, 520)),
            satMin = 0.15f, hueMin = 160f, hueMax = 240f
        )
    )
)

private val haruRegions = CharacterRegions(
    textures = listOf(
        "haru/haru.1024/texture_00.png",
        "haru/haru.1024/texture_01.png",
        "haru/haru.1024/texture_02.png"
    ),
    regions = listOf(
        // Whole of texture 1 is the lavender hair (hue ≈ 270°). The face atlas
        // (texture 0) has no region at all.
        Region(
            key = "hair", labelRes = R.string.live2d_region_hair,
            rects = listOf(RegionRect(1, 0, 0, 1024, 1024)),
            satMin = 0.12f, hueMin = 235f, hueMax = 320f
        ),
        // Navy blazer + sleeves + skirt + white shirt of texture 2 — everything in
        // the 180–260° band. There is deliberately no separate skirt region: the
        // plum/red cluster in the middle of this atlas (h≈331°) is the MOUTH and
        // bow pieces, not a skirt — recoloring it drew a ring around Haru's mouth.
        Region(
            key = "clothes", labelRes = R.string.live2d_region_clothes,
            rects = listOf(RegionRect(2, 0, 0, 1024, 1024)),
            satMin = 0.10f, hueMin = 180f, hueMax = 260f
        )
    )
)

private val hiyoriRegions = CharacterRegions(
    textures = listOf(
        "hiyori_pro_en/hiyori_pro_t11.2048/texture_00.png",
        "hiyori_pro_en/hiyori_pro_t11.2048/texture_01.png"
    ),
    regions = listOf(
        // Brown hair (hue ≈ 21°): the right-side mass (full height — the tail runs
        // to the texture bottom), the side ponytail hanging over the front, and the
        // strands hugging the mass's left edge. The dark warm pieces at the top of
        // the eye area are her eyebrows, not hair — they live left of these rects
        // on purpose.
        Region(
            key = "hair", labelRes = R.string.live2d_region_hair,
            rects = listOf(
                RegionRect(0, 1150, 0, 900, 2048),
                RegionRect(0, 600, 440, 360, 880),
                RegionRect(0, 1030, 80, 150, 1200)
            ),
            satMin = 0.05f, valMax = 0.75f, hueMin = 5f, hueMax = 45f
        ),
        // Cream blouse + sleeves. Two entries share the "clothes" key (one slider):
        // the sleeve pieces fill the right side of texture 1 interleaved with her
        // arms/hands, which match the blouse's saturation and value exactly — only
        // a tighter hue window (sleeves ≈38–40° vs skin ≈32–34°) keeps them out.
        // That window must NOT apply to the central mass (its shading drifts below
        // 35° and would go patchy), hence the split. Note the cream/white pieces at
        // (660..1170, 140..690) are her LEGS and knee socks — not sleeves — and are
        // deliberately left out. First-match-wins: the sleeve entry is checked first.
        // Cream top + sleeves. Three entries share the "clothes" key (one slider):
        // A1) the sleeve cluster and the jacket's bottom-right piece. Hue separates
        //     here: fabric and its darker shading sit at hue ≥35° while the
        //     interleaved hands never exceed ≈34.6°.
        // A2) the hue 30–35° band of the same strip, saturation-capped at 0.22 —
        //     the fabric's AA edges dip below 35° too, and in that band only the
        //     hands are s≥0.25, so the cap admits fabric and keeps skin out. (A
        //     single 0.22 ceiling applied everywhere cut the darker fabric folds —
        //     the "spots not processed".)
        // B) the big central mass, whose own shading drifts below 35°, on the wide
        //    hue gate. The warm-white tubes mid-atlas are her SOCKS (over the legs)
        //    and the pale piece top-left of texture 0 is her face — neither is ever
        //    a rect here.
        // Cream top + sleeves. Three entries share the "clothes" key (one slider):
        // 1) Warm fabric, hue ≥35° — the sleeve cluster, the jacket's bottom-right
        //    piece, and the central mass extended over the arm band to x=730 so no
        //    rect edge cuts through it. The forearm crossing this area is hue
        //    ≈32°/s≈0.28 and never passes: an earlier 25° floor admitted its
        //    left half only, leaving a visible seam at the old rect boundary.
        // 2) The hue 30–35° band, saturation-capped at 0.22 — the fabric's AA
        //    edges dip below 35°, and in that band only skin is s≥0.25.
        // 3) The jacket's shadows are painted COOL (lavender-gray, hue ≈195–270°),
        //    not as darker cream. The navy skirt stays out by value and saturation
        //    (its pieces are v≤0.41 or s≥0.47). The warm-white tubes mid-atlas are
        //    her SOCKS (over the legs) and the pale piece top-left of texture 0 is
        //    her face — neither is ever a rect here.
        // The central rect is split around the legs: above y≈700 the sock
        // (x 686–860, hue ≈41° — passes the warm gate!) and the thigh (x 642–890,
        // shading hue ≈32° — passes the band) own everything right of x=640, while
        // the shaded arm band the user wants shifted stays left of x=640 there.
        // Below y=700 the band drifts right to x≈724 with no leg pieces around, so
        // a lower-zone rect covers it — including (689,1038) — and the legs never
        // get a rect.
        Region(
            key = "clothes", labelRes = R.string.live2d_region_clothes,
            rects = listOf(
                RegionRect(1, 1280, 0, 740, 2048),
                RegionRect(1, 1220, 230, 70, 300),
                RegionRect(1, 1010, 1780, 200, 100),
                RegionRect(1, 40, 40, 600, 1164),
                RegionRect(1, 560, 700, 180, 480)
            ),
            satMin = 0.05f, valMin = 0.30f, hueMin = 35f, hueMax = 55f
        ),
        Region(
            key = "clothes", labelRes = R.string.live2d_region_clothes,
            rects = listOf(
                RegionRect(1, 1280, 0, 740, 2048),
                RegionRect(1, 1220, 230, 70, 300),
                RegionRect(1, 40, 40, 600, 1164),
                RegionRect(1, 560, 700, 180, 480)
            ),
            // The 30–35° band at full fabric saturation: the jacket's shaded parts
            // are warm tan (hue ≈32–34°, s ≈0.27–0.31), inside this band and beyond
            // the old 0.22 cap. The thighs that must NOT shift sit at hue ≈27° —
            // below the band's floor — so the cap could widen without them.
            satMin = 0.05f, satMax = 0.35f, valMin = 0.30f, hueMin = 30f, hueMax = 35f
        ),
        Region(
            key = "clothes", labelRes = R.string.live2d_region_clothes,
            rects = listOf(
                RegionRect(1, 40, 40, 600, 1164),
                RegionRect(1, 560, 700, 180, 480),
                RegionRect(1, 1280, 0, 740, 2048)
            ),
            satMin = 0.06f, satMax = 0.22f, valMin = 0.45f, hueMin = 195f, hueMax = 270f
        ),
        // Navy skirt (+ its gray shading) and the dark socks/shoes bits of tex 1.
        Region(
            key = "skirt", labelRes = R.string.live2d_region_skirt,
            rects = listOf(
                RegionRect(1, 832, 1004, 192, 616),
                RegionRect(1, 1104, 1040, 184, 616),
                RegionRect(1, 24, 1200, 716, 164),
                RegionRect(1, 488, 1792, 460, 220),
                RegionRect(1, 24, 1776, 188, 120)
            ),
            satMin = 0.05f, valMax = 0.75f, hueMin = 200f, hueMax = 250f
        )
    )
)

/** Region tables per character id. Keys are `Live2dCharacters` ids. */
private val characterTables = mapOf(
    "hibiki" to hibikiRegions,
    "haru" to haruRegions,
    "hiyori_pro_en" to hiyoriRegions
)

private const val TAG = "Live2dCustomizer"

/** Directory under `filesDir` that [CubismBridge.LoadFile] checks before the assets. */
private fun overridesRoot(context: Context): File = File(context.filesDir, "live2d_custom")

private fun hueInRange(h: Float, lo: Float, hi: Float): Boolean =
    if (lo <= hi) h in lo..hi else (h >= lo || h <= hi)

/** Rotates an ARGB pixel's hue by [shiftDeg] degrees, keeping saturation and value. */
private fun shiftPixelHue(argb: Int, shiftDeg: Float): Int {
    val a = argb ushr 24 and 0xFF
    val r = argb ushr 16 and 0xFF
    val g = argb ushr 8 and 0xFF
    val b = argb and 0xFF

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = (max - min).toFloat()
    val v = max / 255f
    val s = if (max == 0) 0f else d / max

    var h = when (max) {
        min -> 0f
        r -> {
            var t = (g - b) / d % 6f
            if (t < 0) t += 6f
            60f * t
        }
        g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }

    h = (h + shiftDeg) % 360f
    if (h < 0) h += 360f

    val c = v * s
    val x = c * (1f - kotlin.math.abs(h / 60f % 2f - 1f))
    val m = v - c
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val ri = ((r1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
    val gi = ((g1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
    val bi = ((b1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (a shl 24) or (ri shl 16) or (gi shl 8) or bi
}

/**
 * Applies [hues] (region key -> degrees) to one atlas, first-match-wins over the
 * regions that live on it. Works on a copy; the original stays pristine, so any
 * adjustment always starts from the true asset pixels and resets are exact.
 */
private fun renderTexture(
    original: Bitmap,
    table: CharacterRegions,
    textureIndex: Int,
    hues: Map<String, Float>
): Bitmap {
    // Mutable copy — setPixels below throws IllegalStateException on an immutable
    // bitmap (this exact crash shipped once).
    val result = original.copy(Bitmap.Config.ARGB_8888, true)
    val w = result.width
    val h = result.height
    val pixels = IntArray(w * h)
    result.getPixels(pixels, 0, w, 0, 0, w, h)

    // (region, shift, rect) triples for this atlas, table order preserved — the
    // order is the first-match-wins priority.
    val active = table.regions
        .mapNotNull { region ->
            val shift = hues[region.key] ?: 0f
            if (shift == 0f) null else region to shift
        }
        .flatMap { (region, shift) -> region.rects.filter { it.textureIndex == textureIndex }.map { Triple(region, shift, it) } }
    if (active.isEmpty()) return result

    // Scan the bounding box of the active rects once. Region rects are allowed to
    // overlap by design (first-match-wins resolves the priority), so iterating rect
    // by rect would visit shared pixels twice and shift them twice.
    val x0 = active.minOf { it.third.x }.coerceAtLeast(0)
    val y0 = active.minOf { it.third.y }.coerceAtLeast(0)
    val x1 = active.maxOf { it.third.x + it.third.w }.coerceAtMost(w)
    val y1 = active.maxOf { it.third.y + it.third.h }.coerceAtMost(h)

    for (y in y0 until y1) {
        for (x in x0 until x1) {
            val i = y * w + x
            val argb = pixels[i]
            if (argb ushr 24 and 0xFF == 0) continue
            for ((region, shift, r) in active) {
                if (x < r.x || x >= r.x + r.w || y < r.y || y >= r.y + r.h) continue
                val pr = argb ushr 16 and 0xFF
                val pg = argb ushr 8 and 0xFF
                val pb = argb and 0xFF
                val max = maxOf(pr, pg, pb)
                val min = minOf(pr, pg, pb)
                val d = (max - min).toFloat()
                val v = max / 255f
                val s = if (max == 0) 0f else d / max
                if (s < region.satMin || s > region.satMax || v < region.valMin || v > region.valMax) continue
                val hu = when (max) {
                    min -> 0f
                    pr -> {
                        var t = (pg - pb) / d % 6f
                        if (t < 0) t += 6f
                        60f * t
                    }
                    pg -> 60f * ((pb - pr) / d + 2f)
                    else -> 60f * ((pr - pg) / d + 4f)
                }
                if (!hueInRange(hu, region.hueMin, region.hueMax)) continue
                pixels[i] = shiftPixelHue(argb, shift)
                break
            }
        }
    }

    result.setPixels(pixels, 0, w, 0, 0, w, h)
    return result
}

/** Loads the persisted hue map for a character (missing keys mean 0°). */
fun loadCharacterHues(context: Context, characterId: String): Map<String, Float> {
    val props = Properties()
    val file = File(overridesRoot(context), "$characterId/hues.props")
    runCatching {
        if (file.isFile) file.inputStream().use { props.load(it) }
    }.onFailure { Log.w(TAG, "load hues failed for $characterId", it) }
    // Only keys the character's table still knows — a region removed from the table
    // (skin/eyes once were regions) must not ride along forever in the props file,
    // and must not keep `anyShift` true once its slider is gone.
    val known = characterTables[characterId]?.regions?.map { it.key }?.toSet() ?: emptySet()
    return props.stringPropertyNames()
        .filter { it in known }
        .associateWith { props.getProperty(it).toFloatOrNull() ?: 0f }
}

/**
 * True when the stored hue map mentions regions the character's table no longer has
 * (skin/eyes once were regions). The overrides then predate the current table and may
 * carry those shifts baked into their pixels — the sheet re-applies the filtered hues
 * once on open so the stale renders age out immediately.
 */
private fun hasStaleHueKeys(context: Context, characterId: String): Boolean {
    val known = characterTables[characterId]?.regions?.map { it.key }?.toSet() ?: return false
    val props = Properties()
    val file = File(overridesRoot(context), "$characterId/hues.props")
    runCatching { if (file.isFile) file.inputStream().use { props.load(it) } }
    return props.stringPropertyNames().any { it !in known }
}

/**
 * One customization session: caches the character's pristine atlases for the time the
 * sheet is open, regenerates tinted copies for any hue map, and persists results.
 * Always regenerates from the pristine copies — never accumulates shifts — so any
 * sequence of slider moves stays reversible down to the exact original pixels.
 */
internal class Live2dCharacterEditor(private val context: Context, private val characterId: String) {
    private val table = characterTables[characterId]
    private var originals: MutableList<Bitmap?>? = null

    private fun originalsNow(): List<Bitmap?> {
        val cached = originals
        if (cached != null) return cached
        val list = mutableListOf<Bitmap?>()
        if (table != null) {
            for (path in table.textures) {
                list += runCatching {
                    // inPremultiplied=false is load-bearing, not a nicety: a
                    // premultiplied decode zeroes the RGB under soft alpha edges, and
                    // the PNG re-encode then bakes that in. The Cubism renderer blends
                    // straight alpha, so every re-encoded atlas would grow a dark
                    // halo along anti-aliased outlines — first seen as a ring around
                    // Haru's mouth, from any slider. Straight decode → straight
                    // get/setPixels → straight encode leaves the pixels bit-identical
                    // wherever no region matched.
                    context.assets.open(path).use {
                        BitmapFactory.decodeStream(
                            it, null,
                            BitmapFactory.Options().apply {
                                inPremultiplied = false
                                inScaled = false
                            }
                        )
                    }
                }.getOrNull()
            }
        }
        originals = list
        return list
    }

    /** The sheet's rows, or null for a character without a table. */
    fun regions(): List<Region>? = table?.regions

    /** Frees the cached pristine atlases (sheet closed / screen left). */
    fun release() {
        originals = null
    }

    /**
     * Renders every atlas with [hues] applied and persists the result:
     * tinted PNGs go to the override directory [CubismBridge.LoadFile] prefers, and
     * an all-zero map deletes them again (assets take back over). [pushPreview] is
     * invoked per atlas with PNG bytes for the in-place native texture swap.
     */
    suspend fun apply(hues: Map<String, Float>, pushPreview: (textureIndex: Int, png: ByteArray) -> Unit) {
        val t = table ?: return
        val bitmaps = withContext(Dispatchers.Default) {
            val src = originalsNow()
            t.textures.indices.mapNotNull { i ->
                src.getOrNull(i)?.let { i to renderTexture(it, t, i, hues) }
            }
        }
        val anyShift = hues.values.any { it != 0f }
        withContext(Dispatchers.IO) {
            val root = File(overridesRoot(context), characterId)
            // An earlier build wrote textures under an extra nested character dir
            // (<root>/<id>/<id>.<res>/…); the read side (CubismBridge.LoadFile)
            // probes the asset-relative path directly under live2d_custom, which
            // already begins with the id — so those files were never found. Clean
            // the nested leftovers off devices that ran it.
            File(root, characterId).deleteRecursively()
            if (anyShift) {
                root.mkdirs()
                for ((i, bmp) in bitmaps) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    // Asset-relative, id already included — this MUST byte-match the
                    // path CubismBridge.LoadFile probes or fresh model loads (call
                    // screen, restart) silently fall back to the bundled asset.
                    val out = File(overridesRoot(context), t.textures[i])
                    out.parentFile?.mkdirs()
                    runCatching {
                        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        val bytes = out.readBytes()
                        pushPreview(i, bytes)
                    }.onFailure { Log.w(TAG, "persist texture failed: $characterId/$i", it) }
                }
                Properties().apply {
                    hues.forEach { (k, v) -> setProperty(k, v.toString()) }
                }.store(File(root, "hues.props").outputStream(), null)
            } else {
                root.deleteRecursively()
                // Restore the pristine pixels in the live preview too.
                for ((i, bmp) in bitmaps) {
                    runCatching {
                        val bos = java.io.ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                        pushPreview(i, bos.toByteArray())
                    }
                }
            }
            withContext(Dispatchers.Default) { release() }
        }
    }
}

/**
 * The customization sheet itself: one hue slider per region of the shown character,
 * live preview through the native texture swap, and a reset to the shipped colours.
 * Changes are write-through — every applied slider state is persisted when it is
 * previewed, so dismissing the sheet keeps exactly what was last shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterCustomizeSheet(
    characterId: String,
    glView: GLSurfaceView?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editor = remember(characterId) { Live2dCharacterEditor(context, characterId) }
    DisposableEffect(characterId) {
        onDispose { editor.release() }
    }

    val regions = editor.regions() ?: emptyList()
    var hues by remember(characterId) {
        mutableStateOf(loadCharacterHues(context, characterId))
    }
    var applying by remember { mutableStateOf(false) }
    var applyJob by remember { mutableStateOf<Job?>(null) }

    fun apply(newHues: Map<String, Float>) {
        applyJob?.cancel()
        applying = true
        applyJob = scope.launch {
            try {
                editor.apply(newHues) { index, png ->
                    glView?.queueEvent {
                        CubismBridge.nativeUpdateTexture(png, index)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed render/persist must degrade to "preview did not update",
                // never take the process down (an uncaught exception in this
                // scope-launched coroutine is fatal).
                Log.w(TAG, "apply hues failed for $characterId", e)
            } finally {
                applying = false
            }
        }
    }

    // One-time migration: overrides saved by a build whose table had more regions
    // (skin/eyes) may carry those shifts baked into their pixels. Re-applying the
    // filtered hues rewrites them from the current table — or deletes them when
    // nothing is shifted anymore.
    LaunchedEffect(characterId) {
        if (hasStaleHueKeys(context, characterId)) apply(hues)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // No sheet title — it opens from the picker's own "Customize" button, and
            // the labelled sliders explain themselves; the row was dead height.
            if (applying) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
            // One slider per region key — hiyori's clothes is two table entries
            // (sleeve hue gate vs central-mass gate) but a single row.
            regions.distinctBy { it.key }.forEach { region ->
                val value = hues[region.key] ?: 0f
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text(
                        stringResource(region.labelRes),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (value >= 0) "+${value.toInt()}°" else "${value.toInt()}°",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = value,
                    onValueChange = { hues = hues + (region.key to it) },
                    onValueChangeFinished = { apply(hues) },
                    valueRange = -180f..180f,
                    enabled = !applying
                )
            }
            TextButton(
                onClick = {
                    hues = regions.associate { it.key to 0f }
                    apply(hues)
                },
                enabled = !applying,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp, bottom = 24.dp)
            ) {
                Text(stringResource(R.string.character_customize_reset))
            }
        }
    }
}
