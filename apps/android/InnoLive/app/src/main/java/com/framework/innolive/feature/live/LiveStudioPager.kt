package com.framework.innolive.feature.live

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import kotlinx.coroutines.launch

/** 카메라 미리보기 위에서 좌우로 넘기는 방송 화면 페이지입니다. 화면 왼쪽부터 순서대로 놓입니다. */
enum class LiveStudioPage(@param:StringRes val labelRes: Int) {
    CAMERA(R.string.live_page_camera),
    HOME(R.string.live_page_home),
    SETTINGS(R.string.live_page_settings),
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
    val onOpen: () -> Unit,
    val onClose: () -> Unit,
    val content: @Composable () -> Unit,
)

/** 상단 바와 페이지 표시가 차지하는 높이입니다. 각 페이지는 이 아래부터 내용을 둡니다. */
internal val LiveStudioTopInset = 64.dp
private val LiveStudioTopBarHeight = 48.dp
private val SettingsScrim = Color.Black.copy(alpha = 0.4f)

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

    Box(modifier.fillMaxSize()) {
        preview()
        if (settings != null) {
            // 설정 페이지가 보이는 만큼 미리보기를 어둡게 해 설정 내용을 읽기 쉽게 합니다.
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = (pagerState.currentPage + pagerState.currentPageOffsetFraction - homeIndex)
                        .coerceIn(0f, 1f)
                }.background(SettingsScrim),
            )
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = canSwipe && !pinnedToHome && settings?.canLeaveBySwipe != false,
            // 모든 페이지를 계속 그려 방송 대화상자와 입력 상태가 페이지를 넘겨도 유지되게 합니다.
            beyondViewportPageCount = LiveStudioPage.entries.size - 1,
        ) { index ->
            Box(Modifier.fillMaxSize().semantics { if (index != pagerState.currentPage) hideFromAccessibility() }) {
                when (LiveStudioPage.entries[index]) {
                    LiveStudioPage.CAMERA -> cameraPage()
                    LiveStudioPage.HOME -> homePage()
                    LiveStudioPage.SETTINGS -> settings?.let { page ->
                        LiveSettingsPanel(onDismiss = { page.onClose() }, content = page.content)
                    }
                }
            }
        }
        Column(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = LiveStudioTopBarHeight), content = topBar)
            LiveStudioPageIndicator(
                pageCount = pagerState.pageCount,
                currentPage = pagerState.currentPage,
                modifier = Modifier.height(LiveStudioTopInset - LiveStudioTopBarHeight),
            )
        }
    }
}

/** Figma 설정 창처럼 상단 바 아래에 반투명 창을 띄우고, 창 밖을 누르면 기본 화면으로 돌아갑니다. */
@Composable
private fun LiveSettingsPanel(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val title = stringResource(R.string.settings_title)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss))
        Surface(
            modifier = Modifier.align(Alignment.TopCenter)
                .padding(start = 16.dp, end = 16.dp, top = LiveStudioTopInset, bottom = 12.dp)
                .widthIn(max = 600.dp).fillMaxSize()
                .semantics { paneTitle = title },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            // 반투명 배경은 테마 색과 달라 글자색을 자동으로 고르지 못하므로 직접 지정합니다.
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 4.dp,
        ) { content() }
    }
}

@Composable
private fun LiveStudioPageIndicator(pageCount: Int, currentPage: Int, modifier: Modifier = Modifier) {
    val pageName = stringResource(LiveStudioPage.entries[currentPage].labelRes)
    val description = stringResource(R.string.live_page_indicator, currentPage + 1, pageCount, pageName)
    Row(
        modifier = modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            Box(
                Modifier.size(7.dp).background(
                    if (index == currentPage) Color.White else Color.White.copy(alpha = 0.45f),
                    CircleShape,
                ),
            )
        }
    }
}
