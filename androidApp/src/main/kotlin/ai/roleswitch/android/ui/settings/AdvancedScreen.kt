//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.roleswitch.android.R
import ai.roleswitch.android.RolesWitchApp
import ai.roleswitch.android.service.LitertModel
import ai.roleswitch.android.service.LitertModels
import ai.roleswitch.android.settings.SettingsManager
import ai.roleswitch.android.ui.components.ConfirmDialog
import ai.roleswitch.android.ui.components.appOutlinedTextFieldColors

/**
 * Read/write adapter for the six OpenAI sampling/token fields. Lets [AdvancedScreen]
 * serve both the global settings (backed by [SettingsManager]) and a thread's custom
 * overrides (backed by its `custom*` columns). Implementations capture the current
 * values at composition; edits flow through [save].
 */
internal interface AdvancedBinding {
    val temperature: String
    val topP: String
    val n: String
    val maxCompletionTokens: String
    val presencePenalty: String
    val frequencyPenalty: String

    fun save(
        temperature: String,
        topP: String,
        n: String,
        maxCompletionTokens: String,
        presencePenalty: String,
        frequencyPenalty: String
    )
}

/** Global Advanced screen entry point — backed by the app-wide [SettingsManager]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedScreen(app: RolesWitchApp, onBack: () -> Unit) {
    AdvancedScreen(
        binding = remember { GlobalAdvancedBinding(app.settingsManager) },
        onBack = onBack
    )
}

private class GlobalAdvancedBinding(private val sm: SettingsManager) : AdvancedBinding {
    override val temperature: String get() = sm.temperature.value
    override val topP: String get() = sm.topP.value
    override val n: String get() = sm.n.value
    override val maxCompletionTokens: String get() = sm.maxCompletionTokens.value
    override val presencePenalty: String get() = sm.presencePenalty.value
    override val frequencyPenalty: String get() = sm.frequencyPenalty.value

    override fun save(
        temperature: String,
        topP: String,
        n: String,
        maxCompletionTokens: String,
        presencePenalty: String,
        frequencyPenalty: String
    ) {
        sm.setTemperature(temperature)
        sm.setTopP(topP)
        sm.setN(n)
        sm.setMaxCompletionTokens(maxCompletionTokens)
        sm.setPresencePenalty(presencePenalty)
        sm.setFrequencyPenalty(frequencyPenalty)
    }
}

/**
 * The on-device counterpart of [AdvancedBinding]: the parameters LiteRT-LM actually
 * takes, in the same shape so [LitertAdvancedScreen] can reuse this screen's chrome.
 *
 * Values are strings for the same reason the OpenAI ones are — blank is a first-class
 * state meaning "use the selected model's own default" from `LitertModels`.
 */
internal interface LitertAdvancedBinding {
    val maxTokens: String
    val topK: String
    val topP: String
    val temperature: String

    /** `"gpu"` or `"cpu"`; blank means the model's default. */
    val accelerator: String

    /**
     * The model this scope resolves to, or null when none is selected. Carried on the
     * binding rather than read from `SettingsManager` in the screen because the
     * per-thread scope resolves it through its own `custom*` columns; it supplies the
     * defaults shown behind the empty fields.
     */
    val model: LitertModel?

    fun save(
        maxTokens: String,
        topK: String,
        topP: String,
        temperature: String,
        accelerator: String
    )
}

/**
 * Advanced parameters for the on-device engine.
 *
 * Deliberately the same screen as the OpenAI variant — same chrome, same
 * [AdvancedField]s, same toolbar Save, same discard prompt, same centred Reset —
 * because from the user's side it is the same thing, only the parameters differ.
 *
 * The accelerator leads, as a single toggle rather than a picker: it is the one setting
 * here that decides whether the model runs *at all* on a given phone, so it belongs above
 * the sampling parameters rather than below them. CPU is the default because the GPU path
 * needs a working OpenCL driver and simply fails to build without one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LitertAdvancedScreen(
    app: RolesWitchApp,
    binding: LitertAdvancedBinding,
    onBack: () -> Unit
) {
    var maxTokens by remember { mutableStateOf(binding.maxTokens) }
    var topK by remember { mutableStateOf(binding.topK) }
    var topP by remember { mutableStateOf(binding.topP) }
    var temperature by remember { mutableStateOf(binding.temperature) }
    // Blank means "the model's default", which is CPU for every model in the registry.
    var useGpu by remember { mutableStateOf(binding.accelerator.equals("gpu", ignoreCase = true)) }

    var maxTokensError by remember { mutableStateOf(false) }
    var topKError by remember { mutableStateOf(false) }
    var topPError by remember { mutableStateOf(false) }
    var temperatureError by remember { mutableStateOf(false) }

    var showDiscardDialog by remember { mutableStateOf(false) }

    // Captured once, alongside the editable copies above, so "changed" is measured against
    // what the screen opened with. Trimmed on both sides: trailing whitespace saves
    // identically, so it should not raise a Save button.
    val original = remember {
        listOf(binding.maxTokens, binding.topK, binding.topP, binding.temperature).map { it.trim() }
    }
    // Compared as the boolean the toggle actually shows, not as the string that gets
    // written: a blank stored accelerator and a stored `"cpu"` both render as off, so
    // treating them as different would open the screen already dirty.
    val originalUseGpu = remember { binding.accelerator.equals("gpu", ignoreCase = true) }
    val dirty = listOf(maxTokens, topK, topP, temperature).map { it.trim() } != original ||
        useGpu != originalUseGpu

    // Blank fields fall back to these at generate time, so showing them as placeholders
    // is the only way the user can see what "empty" actually means. Null while no model
    // is selected — there is nothing to fall back to yet.
    val model = binding.model

    /** Validates every field, then saves and leaves. Invalid input keeps the user here with
     *  the offending fields marked — the same contract the old bottom Save button had. */
    fun save() {
        val maxTokensTrimmed = maxTokens.trim()
        val topKTrimmed = topK.trim()

        // Bounded by the checkpoint's context window where it publishes one: above it the
        // engine either fails to build or is killed for memory, and neither says why.
        val ceiling = binding.model?.maxContextLength
        maxTokensError = maxTokensTrimmed.isNotEmpty() &&
            (
                maxTokensTrimmed.toIntOrNull()
                    ?.let { it > 0 && (ceiling == null || it <= ceiling) } != true
                )
        topKError = topKTrimmed.isNotEmpty() &&
            (topKTrimmed.toIntOrNull()?.let { it > 0 } != true)
        topPError = topP.isNotEmpty() && topP.toFloatOrNull() == null
        temperatureError = temperature.isNotEmpty() && temperature.toFloatOrNull() == null

        if (!maxTokensError && !topKError && !topPError && !temperatureError) {
            binding.save(
                maxTokensTrimmed,
                topKTrimmed,
                topP,
                temperature,
                if (useGpu) "gpu" else "cpu"
            )
            // Only these two are baked into the loaded engine (they are what `EngineKey`
            // is built from), so only they make what is resident stale. Dropping it here
            // rather than waiting for the next chat screen frees the gigabytes while the
            // user is still in settings.
            //
            // Guarded, because the sampler values and the thinking flag are applied per
            // turn: releasing on those would throw away a loaded model and charge the
            // user another cold start for a setting that never touched the engine.
            if (maxTokensTrimmed != original[0] || useGpu != originalUseGpu) {
                app.liteRtService.release()
            }
            onBack()
        }
    }

    fun handleBack() {
        if (dirty) showDiscardDialog = true else onBack()
    }

    // The toolbar arrow routes through handleBack itself; this covers the system back —
    // gesture or button — which would otherwise pop the screen straight past the prompt.
    // Disabled while clean so an unedited screen keeps the default back behaviour.
    BackHandler(enabled = dirty) { showDiscardDialog = true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.advanced)) },
                navigationIcon = {
                    IconButton(onClick = ::handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // Appears only once something has changed, as on the profile screen.
                    if (dirty) {
                        IconButton(onClick = ::save) {
                            Icon(Icons.Default.Check, stringResource(R.string.save))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            SettingsCard {
                SettingsToggle(
                    title = stringResource(R.string.litert_use_gpu),
                    subtitle = stringResource(R.string.litert_use_gpu_subtitle),
                    checked = useGpu,
                    onCheckedChange = { useGpu = it }
                )
            }

            Spacer(Modifier.height(12.dp))

            AdvancedField(
                label = stringResource(R.string.litert_max_tokens),
                // The ceiling is only worth naming when there is one to hit.
                hint = model?.maxContextLength?.let {
                    stringResource(R.string.litert_max_tokens_hint_bounded, it)
                } ?: stringResource(R.string.litert_max_tokens_hint),
                value = maxTokens,
                isError = maxTokensError,
                keyboardType = KeyboardType.Number,
                placeholder = model?.defaultMaxTokens?.toString(),
                onValueChange = { maxTokens = it; maxTokensError = false }
            )

            Spacer(Modifier.height(12.dp))

            AdvancedField(
                label = stringResource(R.string.litert_top_k),
                hint = stringResource(R.string.litert_top_k_hint),
                value = topK,
                isError = topKError,
                keyboardType = KeyboardType.Number,
                placeholder = model?.defaultTopK?.toString(),
                onValueChange = { topK = it; topKError = false }
            )

            Spacer(Modifier.height(12.dp))

            AdvancedField(
                label = stringResource(R.string.advanced_top_p),
                hint = stringResource(R.string.litert_top_p_hint),
                value = topP,
                isError = topPError,
                keyboardType = KeyboardType.Text,
                placeholder = model?.defaultTopP?.toString(),
                onValueChange = { topP = it; topPError = false }
            )

            Spacer(Modifier.height(12.dp))

            AdvancedField(
                label = stringResource(R.string.advanced_temperature),
                hint = stringResource(R.string.litert_temperature_hint),
                value = temperature,
                isError = temperatureError,
                keyboardType = KeyboardType.Text,
                placeholder = model?.defaultTemperature?.toString(),
                onValueChange = { temperature = it; temperatureError = false }
            )

            Spacer(Modifier.height(24.dp))

            // Clears the fields only — nothing is written until Save, so backing out
            // after a Reset leaves the stored values untouched. It counts as a change
            // like any other, which is what raises Save and arms the discard prompt.
            TextButton(
                onClick = {
                    // Back to the checkpoint's own defaults — the same values selecting
                    // the model wrote — rather than blank. Blank resolves to the same
                    // numbers at generate time, but showing them is the point of
                    // prefilling in the first place.
                    maxTokens = model?.defaultMaxTokens?.toString().orEmpty()
                    topK = model?.defaultTopK?.toString().orEmpty()
                    topP = model?.defaultTopP?.toString().orEmpty()
                    temperature = model?.defaultTemperature?.toString().orEmpty()
                    useGpu = model?.defaultAccelerator.equals("gpu", ignoreCase = true)
                    maxTokensError = false; topKError = false
                    topPError = false; temperatureError = false
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(stringResource(R.string.reset))
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDiscardDialog) {
        ConfirmDialog(
            text = stringResource(R.string.discard_changes),
            confirmLabel = stringResource(R.string.discard),
            destructive = true,
            onConfirm = {
                showDiscardDialog = false
                onBack()
            },
            onDismiss = { showDiscardDialog = false }
        )
    }
}

/** Global on-device Advanced entry point — backed by the app-wide [SettingsManager]. */
@Composable
fun LitertAdvancedScreen(app: RolesWitchApp, onBack: () -> Unit) {
    LitertAdvancedScreen(
        app = app,
        binding = remember { GlobalLitertAdvancedBinding(app.settingsManager) },
        onBack = onBack
    )
}

private class GlobalLitertAdvancedBinding(private val sm: SettingsManager) : LitertAdvancedBinding {
    override val maxTokens: String get() = sm.litertMaxTokens.value
    override val topK: String get() = sm.litertTopK.value
    override val topP: String get() = sm.litertTopP.value
    override val temperature: String get() = sm.litertTemperature.value
    override val accelerator: String get() = sm.litertAccelerator.value
    override val model: LitertModel? get() = LitertModels.get(sm.litertModel.value)

    override fun save(
        maxTokens: String,
        topK: String,
        topP: String,
        temperature: String,
        accelerator: String
    ) {
        sm.setLitertMaxTokens(maxTokens)
        sm.setLitertTopK(topK)
        sm.setLitertTopP(topP)
        sm.setLitertTemperature(temperature)
        sm.setLitertAccelerator(accelerator)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdvancedScreen(binding: AdvancedBinding, onBack: () -> Unit) {
    var t by remember { mutableStateOf(binding.temperature) }
    var tp by remember { mutableStateOf(binding.topP) }
    var nn by remember { mutableStateOf(binding.n) }
    var mct by remember { mutableStateOf(binding.maxCompletionTokens) }
    var pp by remember { mutableStateOf(binding.presencePenalty) }
    var fp by remember { mutableStateOf(binding.frequencyPenalty) }

    var tError by remember { mutableStateOf(false) }
    var tpError by remember { mutableStateOf(false) }
    var nError by remember { mutableStateOf(false) }
    var mctError by remember { mutableStateOf(false) }
    var ppError by remember { mutableStateOf(false) }
    var fpError by remember { mutableStateOf(false) }

    var showDiscardDialog by remember { mutableStateOf(false) }

    // Captured once, alongside the editable copies above, so "changed" is measured against
    // what the screen opened with. Trimmed on both sides: trailing whitespace saves
    // identically, so it should not raise a Save button.
    val original = remember {
        listOf(
            binding.temperature, binding.topP, binding.n,
            binding.maxCompletionTokens, binding.presencePenalty, binding.frequencyPenalty
        ).map { it.trim() }
    }
    val dirty = listOf(t, tp, nn, mct, pp, fp).map { it.trim() } != original

    /** Validates every field, then saves and leaves. Invalid input keeps the user here with
     *  the offending fields marked — the same contract the old bottom Save button had. */
    fun save() {
        val nTrimmed = nn.trim()
        val mctTrimmed = mct.trim()

        tError = t.isNotEmpty() && t.toDoubleOrNull() == null
        tpError = tp.isNotEmpty() && tp.toDoubleOrNull() == null
        nError = nTrimmed.isNotEmpty() && (nTrimmed.toIntOrNull() ?: 0) <= 0
        mctError = mctTrimmed.isNotEmpty() && (mctTrimmed.toIntOrNull() ?: 0) <= 0
        ppError = pp.isNotEmpty() && pp.toDoubleOrNull() == null
        fpError = fp.isNotEmpty() && fp.toDoubleOrNull() == null

        if (!tError && !tpError && !nError && !mctError && !ppError && !fpError) {
            binding.save(t, tp, nTrimmed, mctTrimmed, pp, fp)
            onBack()
        }
    }

    fun handleBack() {
        if (dirty) showDiscardDialog = true else onBack()
    }

    // The toolbar arrow routes through handleBack itself; this covers the system back —
    // gesture or button — which would otherwise pop the screen straight past the prompt.
    // Disabled while clean so an unedited screen keeps the default back behaviour.
    BackHandler(enabled = dirty) { showDiscardDialog = true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.advanced)) },
                navigationIcon = {
                    IconButton(onClick = ::handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // Appears only once something has changed, as on the profile screen.
                    if (dirty) {
                        IconButton(onClick = ::save) {
                            Icon(Icons.Default.Check, stringResource(R.string.save))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            AdvancedField(
                label = stringResource(R.string.advanced_temperature),
                hint = stringResource(R.string.advanced_temperature_hint),
                value = t,
                isError = tError,
                keyboardType = KeyboardType.Text,
                onValueChange = { t = it; tError = false }
            )

            Spacer(Modifier.height(12.dp))

            AdvancedField(
                label = stringResource(R.string.advanced_top_p),
                hint = stringResource(R.string.advanced_top_p_hint),
                value = tp,
                isError = tpError,
                keyboardType = KeyboardType.Text,
                onValueChange = { tp = it; tpError = false }
            )

            Spacer(Modifier.height(12.dp))

            AdvancedField(
                label = stringResource(R.string.advanced_n),
                hint = stringResource(R.string.advanced_n_hint),
                value = nn,
                isError = nError,
                keyboardType = KeyboardType.Number,
                onValueChange = { nn = it; nError = false }
            )

            Spacer(Modifier.height(12.dp))

            AdvancedField(
                label = stringResource(R.string.advanced_max_completion_tokens),
                hint = stringResource(R.string.advanced_max_completion_tokens_hint),
                value = mct,
                isError = mctError,
                keyboardType = KeyboardType.Number,
                onValueChange = { mct = it; mctError = false }
            )

            Spacer(Modifier.height(12.dp))

            AdvancedField(
                label = stringResource(R.string.advanced_presence_penalty),
                hint = stringResource(R.string.advanced_presence_penalty_hint),
                value = pp,
                isError = ppError,
                keyboardType = KeyboardType.Text,
                onValueChange = { pp = it; ppError = false }
            )

            Spacer(Modifier.height(12.dp))

            AdvancedField(
                label = stringResource(R.string.advanced_frequency_penalty),
                hint = stringResource(R.string.advanced_frequency_penalty_hint),
                value = fp,
                isError = fpError,
                keyboardType = KeyboardType.Text,
                onValueChange = { fp = it; fpError = false }
            )

            Spacer(Modifier.height(24.dp))

            // Clears the fields only — nothing is written until Save, so backing out
            // after a Reset leaves the stored values untouched. It counts as a change
            // like any other, which is what raises Save and arms the discard prompt.
            TextButton(
                onClick = {
                    t = ""; tp = ""; nn = ""; mct = ""; pp = ""; fp = ""
                    tError = false; tpError = false; nError = false
                    mctError = false; ppError = false; fpError = false
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(stringResource(R.string.reset))
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDiscardDialog) {
        ConfirmDialog(
            text = stringResource(R.string.discard_changes),
            confirmLabel = stringResource(R.string.discard),
            destructive = true,
            onConfirm = {
                showDiscardDialog = false
                onBack()
            },
            onDismiss = { showDiscardDialog = false }
        )
    }
}

@Composable
private fun AdvancedField(
    label: String,
    hint: String,
    value: String,
    isError: Boolean,
    keyboardType: KeyboardType,
    /** Shown greyed out while the field is empty — the value that will actually be used. */
    placeholder: String? = null,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            isError = isError,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = appOutlinedTextFieldColors()
        )
        if (isError) {
            Text(
                stringResource(R.string.advanced_invalid_number),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        } else {
            Text(
                hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}
