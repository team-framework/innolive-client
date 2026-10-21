package com.framework.innolive.feature.live

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import com.framework.innolive.ui.theme.ModalSurface
import com.framework.innolive.ui.theme.ModalTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import com.framework.innolive.R
import kotlinx.coroutines.launch

/** 카메라 미리보기 위에서 좌우로 넘기는 방송 화면 페이지입니다. 화면 왼쪽부터 순서대로 놓입니다. */
enum class LiveStudioPage(@param:StringRes val labelRes: Int, @param:StringRes val tabRes: Int) {
    CAMERA(R.string.live_page_camera, R.string.live_page_tab_camera),
    HOME(R.string.live_page_home, R.string.live_page_tab_home),
    SETTINGS(R.string.live_page_settings, R.string.live_page_tab_settings),
}

/**
 * 설정 페이지와 앱 백스택의 설정 화면을 잇습니다.
 * 넘겨서 설정 페이지에 멈추면 [onOpen], 벗어나면 [onClose]로 백스택을 맞추고,
 * 백스택이 바뀌면([isOpen]) 페이지를 그쪽으로 옮깁니다.
 */
class LiveSettingsPage(
    val isOpen: Boolean,
    /** 하위 설정이 열려 있거나 계정 삭제 중이면 넘겨서 벗어나지 않고 뒤로 가기·닫기로만 돌아옵니다. */
    val canLeaveBySwipe: Boolean,
    /** 하위 설정 화면처럼 흰 창 위에 내용을 그릴 때입니다. 설정 첫 화면은 카드만 미리보기 위에 띄웁니다. */
    val showsPanel: Boolean = true,
    val onOpen: () -> Unit,
    val onClose: () -> Unit,
    val content: @Composable () -> Unit,
)

/** 상단 바와 페이지 표시가 차지하는 높이입니다. 각 페이지는 이 아래부터 내용을 둡니다. */
internal val LiveStudioTopInset = 96.dp
private val LiveStudioTopBarHeight = 48.dp
private val LiveStudioPageTabsHeight = 44.dp
private val SettingsScrim = Color.Black.copy(alpha = 0.35f)
private val EdgeScrimColor = Color.Black.copy(alpha = 0.4f)
private val EdgeScrimHeight = 120.dp

/**
 * 카메라 미리보기는 고정한 채 그 위의 조작 화면만 좌우로 넘깁니다.
 * 페이지를 넘겨도 미리보기와 송출 캡처는 다시 만들지 않습니다.
 */
@Composable
internal fun LiveStudioPager(
    pagerState: PagerState,
    settings: LiveSettingsPage?,
    canSwipe: Boolean,
    pinnedToHome: Boolean,
    preview: @Composable () -> Unit,
    topBar: @Composable BoxScope.() -> Unit,
    cameraPage: @Composable BoxScope.() -> Unit,
    homePage: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    /** 미리보기 영상 위의 작은 창입니다. 설정 화면이 아닐 때 끌어서 옮길 수 있습니다. */
    pipState: LivePreviewPipState? = null,
    /** 얼굴 선택처럼 미리보기 영상을 직접 눌러야 할 때입니다. 넘김을 멈추고 기본 화면만 보여 줍니다. */
    previewInteractive: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val homeIndex = LiveStudioPage.HOME.ordinal
    val settingsIndex = LiveStudioPage.SETTINGS.ordinal
    val currentSettings by rememberUpdatedState(settings)

    LaunchedEffect(pagerState, settings?.isOpen) {
        val isOpen = settings?.isOpen ?: return@LaunchedEffect
        if (isOpen && pagerState.targetPage != settingsIndex) {
            pagerState.animateScrollToPage(settingsIndex)
        } else if (!isOpen && pagerState.settledPage == settingsIndex && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(homeIndex)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val current = currentSettings ?: return@collect
            if (page == settingsIndex && !current.isOpen) current.onOpen()
            else if (page != settingsIndex && current.isOpen) current.onClose()
        }
    }
    // 방송 안내는 기본 화면의 버튼을 가리키므로 안내 중에는 기본 화면에 머뭅니다.
    LaunchedEffect(pagerState, pinnedToHome) {
        if (pinnedToHome && pagerState.currentPage != homeIndex) pagerState.animateScrollToPage(homeIndex)
    }
    // 설정 화면의 뒤로 가기는 앱 내비게이션이 받습니다. 카메라 화면에서는 기본 화면으로 돌아옵니다.
    BackHandler(enabled = settings?.isOpen != true && pagerState.currentPage != homeIndex) {
        scope.launch { pagerState.animateScrollToPage(homeIndex) }
    }

    val canChangePage = canSwipe && !pinnedToHome && settings?.canLeaveBySwipe != false && !previewInteractive
    val canMovePip = pipState != null && pagerState.currentPage != settingsIndex
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(
        modifier.fillMaxSize().then(
            if (pipState != null) Modifier.livePreviewPipDrag(pipState, canMovePip, scope) else Modifier,
        ),
    ) {
        preview()
        // 미리보기는 SurfaceView라 같은 창 안에서 흐리게 할 수 없습니다. 설정 페이지가 보이는 만큼 어둡게만 합니다.
        fun settingsFraction() =
            (pagerState.currentPage + pagerState.currentPageOffsetFraction - homeIndex).coerceIn(0f, 1f)
        fun homeFraction() =
            1f - kotlin.math.abs(pagerState.currentPage + pagerState.currentPageOffsetFraction - homeIndex).coerceIn(0f, 1f)
        // 밝은 장면에서도 흰 글자와 아이콘이 읽히도록 위아래 가장자리만 옅게 어둡게 합니다.
        // 아래쪽은 조작 요소가 있는 기본 화면에서만 깔아 카메라 화면은 영상을 그대로 보여 줍니다.
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(EdgeScrimHeight)
                .background(Brush.verticalGradient(listOf(EdgeScrimColor, Color.Transparent))),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(EdgeScrimHeight * 2)
                .graphicsLayer { alpha = homeFraction() }
                .background(Brush.verticalGradient(listOf(Color.Transparent, EdgeScrimColor))),
        )
        if (settings != null) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = settingsFraction() }.background(SettingsScrim))
        }
        if (previewInteractive) {
            // 넘기는 영역은 화면 전체의 터치를 받아 아래 미리보기로 전달하지 않으므로, 이때는 기본 화면을 일반 레이어로 그립니다.
            // 버튼이 아닌 곳을 누르면 아래 미리보기의 얼굴 선택이 받습니다.
            Box(Modifier.fillMaxSize()) { homePage() }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = canChangePage,
                // 모든 페이지를 계속 그려 방송 대화상자와 입력 상태가 페이지를 넘겨도 유지되게 합니다.
                beyondViewportPageCount = LiveStudioPage.entries.size - 1,
            ) { index ->
                Box(Modifier.fillMaxSize().semantics { if (index != pagerState.currentPage) hideFromAccessibility() }) {
                    when (LiveStudioPage.entries[index]) {
                        LiveStudioPage.CAMERA -> cameraPage()
                        LiveStudioPage.HOME -> homePage()
                        LiveStudioPage.SETTINGS -> settings?.let { page ->
                            LiveSettingsPanel(
                                showsPanel = page.showsPanel,
                                onDismiss = { page.onClose() },
                                topInset = if (landscape) 12.dp else LiveStudioTopInset,
                                content = page.content,
                            )
                        }
                    }
                }
            }
        }
        if (pipState != null && canMovePip) LivePreviewPipAccessibility(pipState)
        // 가로 화면은 높이가 낮아 설정 페이지에서는 상단 바를 숨기고 설정 창에 높이를 모두 줍니다.
        val hasSettings = settings != null
        val topBarHidden by remember(pagerState, landscape, hasSettings) {
            derivedStateOf { landscape && hasSettings && settingsFraction() >= 1f }
        }
        Column(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().graphicsLayer {
                if (landscape && settings != null) alpha = 1f - settingsFraction()
            }
                // 다 숨긴 상단 바는 놓지 않아, 보이지 않는 페이지 탭과 남은 시간이 아래 설정 창의 터치를 가로채지 않게 합니다.
                // 남은 시간 상태가 처음으로 돌아가지 않도록 구성은 유지하고, 화면 낭독기에서도 감춥니다.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { if (!topBarHidden) placeable.place(0, 0) }
                }
                .then(if (topBarHidden) Modifier.clearAndSetSemantics {} else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = LiveStudioTopBarHeight), content = topBar)
            // iOS처럼 넘기지 않아도 탭으로 페이지를 고를 수 있게 합니다. 넘길 수 없을 때는 탭도 막습니다.
            LiveStudioPageTabs(
                currentPage = pagerState.currentPage,
                enabled = canChangePage,
                onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
                modifier = Modifier.height(LiveStudioPageTabsHeight),
            )
        }
    }
}

/**
 * Figma 설정 창처럼 상단 바 아래에 반투명 창을 띄우고, 창 밖을 누르면 기본 화면으로 돌아갑니다.
 * [showsPanel]이 false면 같은 자리에 창 바탕만 지워 내용(설정 첫 화면의 카드)이 미리보기 위에 바로 보이게 합니다.
 * 창을 바꿔 끼우지 않고 색만 바꿔 안쪽 내비게이션 상태와 전환 애니메이션을 유지합니다.
 */
@Composable
private fun LiveSettingsPanel(
    showsPanel: Boolean,
    onDismiss: () -> Unit,
    topInset: Dp,
    content: @Composable () -> Unit,
) {
    val title = stringResource(R.string.settings_title)
    // 뒤를 흐릴 수 없으므로 비치는 영상이 글자를 방해하지 않게 더 불투명하게 칠합니다.
    val panelColor by animateColorAsState(
        if (showsPanel) SettingsPanelColor else SettingsPanelColor.copy(alpha = 0f),
        label = "settingsPanelColor",
    )
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss))
        // 다른 모달과 같은 흰 유리 창으로 보여 주고, 창 안에서 연 확인 창도 같은 색을 씁니다.
        ModalTheme {
            ModalSurface(
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(start = 16.dp, end = 16.dp, top = topInset, bottom = 12.dp)
                    .widthIn(max = 640.dp).fillMaxSize()
                    .semantics { paneTitle = title },
                shape = RoundedCornerShape(20.dp),
                color = panelColor,
                showsBorder = showsPanel,
                content = content,
            )
        }
    }
}

private val SettingsPanelColor = Color.White.copy(alpha = 0.96f)

/** iOS 방송 화면처럼 반투명 알약 안에 페이지 이름을 두고, 지금 페이지는 흰 글자와 밑줄로 보여 줍니다. */
@Composable
private fun LiveStudioPageTabs(
    currentPage: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(PageTabsBackground)
            .border(1.dp, PageTabsBorder, CircleShape)
            .padding(horizontal = 8.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveStudioPage.entries.forEach { page ->
            val selected = page.ordinal == currentPage
            val pageName = stringResource(page.labelRes)
            Box(
                modifier = Modifier.fillMaxHeight().widthIn(min = 64.dp)
                    .selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.Tab,
                        onClick = { if (!selected) onSelect(page.ordinal) },
                    )
                    .clearAndSetSemantics {
                        contentDescription = pageName
                        this.selected = selected
                        role = Role.Tab
                        if (!enabled) disabled()
                    }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(page.tabRes),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.65f),
                )
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(width = 18.dp, height = 3.dp)
                        .background(if (selected) Color.White else Color.Transparent, CircleShape),
                )
            }
        }
    }
}

private val PageTabsBackground = Color.Black.copy(alpha = 0.45f)
private val PageTabsBorder = Color.White.copy(alpha = 0.16f)
