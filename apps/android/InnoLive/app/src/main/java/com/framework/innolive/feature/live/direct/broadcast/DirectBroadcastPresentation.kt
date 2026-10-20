package com.framework.innolive.feature.live.direct.broadcast

import androidx.annotation.StringRes
import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastEvent
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.BroadcastState
import com.framework.innolive.feature.live.ServerApiException
import com.framework.innolive.feature.live.SessionTarget
import com.framework.innolive.feature.live.canPrepare
import com.framework.innolive.feature.live.direct.DirectPublishResolution
import com.framework.innolive.feature.live.status.BroadcastTargetTone
import com.framework.innolive.feature.live.status.statusLabelRes
import com.framework.innolive.feature.live.status.tone
import com.framework.innolive.feature.settings.PlanMode
import com.framework.innolive.feature.settings.PlanUsage
import com.framework.innolive.ui.text.ServerErrorGuidance
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.text.directPublishErrorGuidance
import java.util.Locale

/*
 * 직접 송출 화면(C8)의 표시 규칙입니다. Compose 없이 계산해 단위 테스트로 확인합니다.
 */

/** 화질 선택지의 표시 순서입니다. 높은 화질이 뒤에 옵니다. */
internal val DirectResolutionOrder = listOf(DirectPublishResolution.HD_720, DirectPublishResolution.FHD)

/** 송출 대상 선택지의 표시 순서입니다. */
internal val DirectProviderOrder = listOf(BroadcastProvider.YOUTUBE, BroadcastProvider.CHZZK)

internal fun DirectPublishResolution.planMode(targetCount: Int): PlanMode =
    PlanMode.current(wireValue, targetCount)

internal fun DirectBroadcastPlan.planMode(): PlanMode = resolution.planMode(providers.size)

/** 요금제를 모르면 서버가 prepare에서 막으므로 화면에서는 막지 않습니다(iOS와 같음). */
private fun PlanUsage?.allows(mode: PlanMode): Boolean = this == null || isAllowed(mode)

/** 요금제가 허용하는 최고 화질입니다. 요금제를 아직 모르면 720p입니다. */
internal fun defaultDirectResolution(plan: PlanUsage?, targetCount: Int): DirectPublishResolution =
    if (plan == null) DirectPublishResolution.HD_720
    else DirectResolutionOrder.lastOrNull { plan.isAllowed(it.planMode(targetCount)) } ?: DirectPublishResolution.HD_720

/**
 * 요금제를 불러온 뒤 적용할 구성입니다. 사용자가 고르지 않았으면 허용되는 최고 화질로 맞추고,
 * 고른 화질이 허용되지 않으면 허용되는 화질로 낮춥니다.
 */
internal fun reconcileDirectBroadcastPlan(
    current: DirectBroadcastPlan,
    plan: PlanUsage?,
    userSelected: Boolean,
): DirectBroadcastPlan {
    val count = current.providers.size
    val resolution = when {
        !userSelected -> defaultDirectResolution(plan, count)
        plan.allows(current.resolution.planMode(count)) -> current.resolution
        else -> defaultDirectResolution(plan, count)
    }
    return current.copy(resolution = resolution)
}

/** 화면에서 고른 플랫폼 하나로 단독 송출 대상을 맞춥니다. 동시 송출 구성은 그대로 둡니다. */
internal fun DirectBroadcastPlan.withSelectedPlatform(provider: BroadcastProvider): DirectBroadcastPlan =
    if (providers.size <= 1 && provider !in providers) copy(providers = setOf(provider)) else this

/** 준비·라이브 중에는 방송 모드를 바꿀 수 없습니다. */
internal fun isDirectBroadcastModeLocked(state: BroadcastState, isPreparing: Boolean): Boolean =
    isPreparing || !state.canPrepare

internal enum class DirectModeBlock {
    /** 준비·라이브 중이라 잠겼습니다. */
    LOCKED,

    /** 요금제에서 쓸 수 없는 방식입니다. 요금제 안내로 연결합니다. */
    PLAN,

    /** 계정을 연결하지 않았거나 다시 연결해야 합니다. */
    ACCOUNT,

    /** 서버 처리는 송출 대상이 하나 이상 남아야 합니다. 온디바이스는 모두 해제해 미리보기만 볼 수 있습니다. */
    LAST_TARGET,
}

internal data class DirectResolutionOption(
    val resolution: DirectPublishResolution,
    val selected: Boolean,
    val block: DirectModeBlock?,
) {
    val enabled: Boolean get() = block == null
}

internal data class DirectProviderOption(
    val provider: BroadcastProvider,
    val selected: Boolean,
    val accountConnected: Boolean,
    val block: DirectModeBlock?,
    /** 요금제가 동시 송출을 허용하지 않아, 고르면 지금 플랫폼 대신 이 플랫폼 하나로 바꿉니다. */
    val replacesSelection: Boolean = false,
) {
    val enabled: Boolean get() = block == null
}

internal enum class DirectModeWarning {
    /** 고른 방식이 요금제에 없습니다. */
    NOT_IN_PLAN,

    /** 고른 방식의 이번 달 시간이 남지 않았습니다. */
    TIME_EXHAUSTED,
}

internal data class DirectBroadcastModeState(
    val plan: DirectBroadcastPlan,
    val locked: Boolean,
    val resolutions: List<DirectResolutionOption>,
    val providers: List<DirectProviderOption>,
    val warning: DirectModeWarning?,
    /** 플랫폼을 모두 해제할 수 있습니다. 온디바이스 처리에서만 켜며, 해제하면 비식별화 미리보기만 엽니다. */
    val allowsNoTarget: Boolean = false,
) {
    /** 요금제 때문에 막힌 선택지가 있으면 요금제 안내를 보여 줍니다. */
    val showsPlanGuidance: Boolean
        get() = warning != null || resolutions.any { it.block == DirectModeBlock.PLAN } ||
            providers.any { it.block == DirectModeBlock.PLAN }
}

internal fun directBroadcastModeState(
    plan: DirectBroadcastPlan,
    planUsage: PlanUsage?,
    accountConnected: (BroadcastProvider) -> Boolean,
    locked: Boolean,
    allowNoTarget: Boolean = false,
): DirectBroadcastModeState {
    val count = plan.providers.size
    val resolutions = DirectResolutionOrder.map { resolution ->
        val selected = resolution == plan.resolution
        DirectResolutionOption(
            resolution = resolution,
            selected = selected,
            block = when {
                locked -> DirectModeBlock.LOCKED
                !selected && !planUsage.allows(resolution.planMode(count)) -> DirectModeBlock.PLAN
                else -> null
            },
        )
    }
    val providers = DirectProviderOrder.map { provider ->
        val selected = provider in plan.providers
        val connected = accountConnected(provider)
        val canAdd = planUsage.allows(plan.resolution.planMode(count + 1))
        // 동시 송출은 안 되지만 단독 송출은 되면, 추가 대신 교체로 다른 플랫폼을 고를 수 있게 합니다.
        val canReplace = !canAdd && planUsage.allows(plan.resolution.planMode(1))
        DirectProviderOption(
            provider = provider,
            selected = selected,
            accountConnected = connected,
            block = when {
                locked -> DirectModeBlock.LOCKED
                selected && count == 1 && !allowNoTarget -> DirectModeBlock.LAST_TARGET
                selected -> null
                !connected -> DirectModeBlock.ACCOUNT
                !canAdd && !canReplace -> DirectModeBlock.PLAN
                else -> null
            },
            replacesSelection = !selected && canReplace,
        )
    }
    val mode = plan.planMode()
    val warning = when {
        planUsage == null || plan.isPreviewOnly -> null
        !planUsage.isAllowed(mode) -> DirectModeWarning.NOT_IN_PLAN
        planUsage.availability(mode)?.seconds == 0L -> DirectModeWarning.TIME_EXHAUSTED
        else -> null
    }
    return DirectBroadcastModeState(plan, locked, resolutions, providers, warning, allowNoTarget)
}

/** 선택을 바꾼 결과입니다. 막힌 선택지거나 모두 해제할 수 없는 구성에서 마지막 대상을 빼면 null입니다. */
internal fun DirectBroadcastModeState.selectResolution(resolution: DirectPublishResolution): DirectBroadcastPlan? =
    resolutions.firstOrNull { it.resolution == resolution }?.takeIf { it.enabled }
        ?.let { plan.copy(resolution = resolution) }

internal fun DirectBroadcastModeState.toggleProvider(provider: BroadcastProvider): DirectBroadcastPlan? {
    val option = providers.firstOrNull { it.provider == provider }?.takeIf { it.enabled } ?: return null
    val next = when {
        option.selected -> plan.providers - provider
        option.replacesSelection -> setOf(provider)
        else -> plan.providers + provider
    }
    return if (next.isEmpty() && !allowsNoTarget) null else plan.copy(providers = next)
}

internal val DirectPublishResolution.labelRes: Int
    @StringRes get() = when (this) {
        DirectPublishResolution.HD_720 -> R.string.direct_mode_resolution_hd
        DirectPublishResolution.FHD -> R.string.direct_mode_resolution_fhd
    }

internal val BroadcastProvider.title: String
    get() = when (this) {
        BroadcastProvider.YOUTUBE -> "YouTube"
        BroadcastProvider.CHZZK -> "CHZZK"
    }

// --- 상태 패널 ---

/** 상태 패널 한 줄입니다. 서버 대상과 직접 송출 대상이 같은 모양을 씁니다. */
internal data class BroadcastTargetRow(
    val provider: String,
    @param:StringRes val labelRes: Int,
    val tone: BroadcastTargetTone,
)

internal fun SessionTarget.toStatusRow(): BroadcastTargetRow = BroadcastTargetRow(provider, statusLabelRes, tone)

/** 방송 전에는 고른 송출 대상을 상태 패널 행으로 보여 줍니다. 세션이 생기면 실제 상태 행으로 바뀝니다. */
internal fun directPlanRows(plan: DirectBroadcastPlan): List<BroadcastTargetRow> =
    DirectProviderOrder.filter { it in plan.providers }.map { provider ->
        BroadcastTargetRow(provider.wireValue, R.string.live_status_target_not_started, BroadcastTargetTone.PROGRESS)
    }

private val SERVER_PROGRESS_PHASES = setOf("preparing", "going_live")

/**
 * 서버 phase와 로컬 RTMP 상태를 합칩니다. 직접 송출에서 서버 `stream.status`는 항상 `idle`이라
 * 라이브 이후의 송출 상태는 로컬 상태로 보여 줍니다. 로컬 상태가 없으면 서버 대상 그대로입니다.
 */
internal fun directTargetRows(
    serverTargets: List<SessionTarget>,
    local: DirectBroadcastUiState,
): List<BroadcastTargetRow> {
    if (local.targets.isEmpty()) return serverTargets.map(SessionTarget::toStatusRow)
    val rows = serverTargets.map { target ->
        val provider = BroadcastProvider.entries.firstOrNull { it.wireValue == target.provider }
        val status = provider?.let(local.targets::get)
        directTargetRow(target, status, local.slate.isNotEmpty())
    }
    // 서버가 대상을 idle로 돌린 뒤에도 로컬 연결 실패는 남겨 원인을 알립니다.
    val failedOnly = local.targets.filter { (provider, status) ->
        status == LocalTargetStatus.FAILED && serverTargets.none { it.provider == provider.wireValue }
    }.keys.sortedBy(DirectProviderOrder::indexOf).map { provider ->
        BroadcastTargetRow(provider.wireValue, R.string.live_status_target_failed, BroadcastTargetTone.ATTENTION)
    }
    return rows + failedOnly
}

private fun directTargetRow(
    target: SessionTarget,
    status: LocalTargetStatus?,
    slate: Boolean,
): BroadcastTargetRow {
    fun row(@StringRes label: Int, tone: BroadcastTargetTone) = BroadcastTargetRow(target.provider, label, tone)
    if (target.status == "stopped" || status == LocalTargetStatus.STOPPED) {
        return row(R.string.live_status_target_stopped, BroadcastTargetTone.ENDED)
    }
    if (status == LocalTargetStatus.FAILED) return row(R.string.live_status_target_failed, BroadcastTargetTone.ATTENTION)
    if (target.broadcastPhase in SERVER_PROGRESS_PHASES) return target.toStatusRow()
    if (status == LocalTargetStatus.RECONNECTING) {
        return row(R.string.live_status_target_reconnecting, BroadcastTargetTone.ATTENTION)
    }
    if (target.broadcastPhase == "prepared") return target.toStatusRow()
    return when (status) {
        LocalTargetStatus.PUBLISHING ->
            if (slate) row(R.string.live_status_target_slate, BroadcastTargetTone.ATTENTION)
            else row(R.string.live_status_target_streaming, BroadcastTargetTone.LIVE)
        LocalTargetStatus.CONNECTING -> row(R.string.live_status_target_connecting, BroadcastTargetTone.PROGRESS)
        else -> row(R.string.live_status_target_checking, BroadcastTargetTone.PROGRESS)
    }
}

/** 준비 후 서버가 세션을 지우기까지의 시간입니다(contracts/api/direct-publish-v1.md 세션 수명). */
internal const val DIRECT_PREPARED_LIMIT_MILLIS = 10 * 60 * 1_000L

/** 마감 1분 전부터 남은 시간을 강조합니다. */
private const val PREPARED_DEADLINE_URGENT_MILLIS = 60 * 1_000L

internal data class DirectStatusWarning(val text: UiText, val urgent: Boolean)

/**
 * 상태 패널 아래 경고입니다. 대기 화면, 가림 처리 실패, 마이크, 발열, 준비 마감 순서로 보여 줍니다.
 * [nowElapsedMillis]는 `SystemClock.elapsedRealtime()` 기준입니다.
 */
internal fun directStatusWarnings(
    local: DirectBroadcastUiState,
    nowElapsedMillis: Long,
): List<DirectStatusWarning> = buildList {
    slateMessageRes(local.slate)?.let { add(DirectStatusWarning(UiText.Resource(it), urgent = false)) }
    if (local.anonymizationFailing) {
        add(DirectStatusWarning(UiText.Resource(R.string.direct_warning_anonymization_failing), urgent = true))
    }
    when (local.microphone) {
        MicrophoneStatus.OK -> Unit
        MicrophoneStatus.UNAVAILABLE ->
            add(DirectStatusWarning(UiText.Resource(R.string.direct_warning_microphone_unavailable), urgent = true))
        MicrophoneStatus.SILENCED ->
            add(DirectStatusWarning(UiText.Resource(R.string.direct_warning_microphone_silenced), urgent = true))
    }
    if (local.thermalWarning) {
        add(DirectStatusWarning(UiText.Resource(R.string.direct_warning_thermal), urgent = true))
    }
    local.preparedDeadlineElapsedMillis?.let { deadline ->
        val remaining = deadline - nowElapsedMillis
        if (remaining <= 0) {
            add(DirectStatusWarning(UiText.Resource(R.string.direct_warning_prepared_expired), urgent = true))
        } else {
            val totalSeconds = (remaining + 999) / 1_000
            add(
                DirectStatusWarning(
                    UiText.Resource(
                        R.string.direct_warning_prepared_deadline,
                        listOf(String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)),
                    ),
                    urgent = remaining <= PREPARED_DEADLINE_URGENT_MILLIS,
                ),
            )
        }
    }
}

/** 사용자 일시 중지가 가장 분명한 이유이므로 먼저 보여 줍니다. */
@StringRes
private fun slateMessageRes(slate: Set<SlateReason>): Int? = when {
    SlateReason.USER_PAUSE in slate -> R.string.direct_warning_slate_paused
    SlateReason.BACKGROUND in slate -> R.string.direct_warning_slate_background
    SlateReason.CAMERA_UNAVAILABLE in slate -> R.string.direct_warning_slate_camera
    else -> null
}

// --- 앱 상황 안내 ---

/** 서버 오류 코드가 없는 직접 송출 상황입니다. 상태 머신이 [toBroadcastEvent]로 화면에 알립니다. */
internal sealed interface DirectBroadcastNotice {
    /** 하트비트 `stop_targets`로 서버가 끝낸 대상입니다. */
    data class TargetStoppedByServer(val provider: BroadcastProvider) : DirectBroadcastNotice

    /** 재연결을 포기해 해당 대상의 방송을 끝냈습니다. */
    data class ConnectionLost(val provider: BroadcastProvider) : DirectBroadcastNotice

    /** `prepared`로 10분이 지나 서버가 세션을 지웠습니다. 다시 준비해야 합니다. */
    data object PreparedExpired : DirectBroadcastNotice

    /** 마이크를 열지 못해 무음으로 송출합니다. */
    data object MicrophoneUnavailable : DirectBroadcastNotice
}

internal fun DirectBroadcastNotice.text(): UiText = when (this) {
    is DirectBroadcastNotice.TargetStoppedByServer ->
        UiText.Resource(R.string.direct_notice_target_stopped_by_server, listOf(provider.title))
    is DirectBroadcastNotice.ConnectionLost ->
        UiText.Resource(R.string.direct_notice_connection_lost, listOf(provider.title))
    DirectBroadcastNotice.PreparedExpired -> UiText.Resource(R.string.direct_notice_prepared_expired)
    DirectBroadcastNotice.MicrophoneUnavailable -> UiText.Resource(R.string.direct_notice_microphone_unavailable)
}

internal fun DirectBroadcastNotice.toBroadcastEvent(): BroadcastEvent = BroadcastEvent.Message(text())

/** 직접 송출 API 오류의 화면 안내입니다. 상태 머신이 [BroadcastEvent.ApiFailure]로 전달합니다. */
internal fun ServerApiException.directGuidance(): ServerErrorGuidance? =
    directPublishErrorGuidance(code, statusCode, helpUrl, field, reason)
