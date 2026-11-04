package com.framework.innolive.app

import android.os.Build

/**
 * 같은 화면이 이미 맨 위에 있으면 다시 쌓지 않습니다. 줄을 빠르게 두 번 누르거나 전환 애니메이션 중에
 * 나가는 화면이 탭을 받아도 같은 화면이 두 번 쌓여 뒤로 가기가 한 번 헛도는 일이 없게 합니다.
 */
internal fun MutableList<AppRoute>.pushIfNotTop(route: AppRoute) {
    if (lastOrNull() != route) add(route)
}

/**
 * 화면 안의 뒤로 버튼이나 옵션 선택은 그 화면이 아직 맨 위일 때만 뒤로 가기로 처리합니다. 이미 닫히는 화면을
 * 연달아 눌러도 아래에 있는 화면까지 닫히거나 앱이 끝나지 않습니다.
 */
internal fun List<AppRoute>.acceptsBackFrom(route: AppRoute): Boolean = lastOrNull() == route

/**
 * Android 12부터 시스템은 작업의 루트 화면에서 뒤로 가기를 누르면 Activity를 끝내지 않고 앱을 뒤로 보냅니다.
 * Android 11에서는 Activity가 끝나 방송 연결까지 닫히므로 같은 동작으로 맞춥니다.
 */
internal fun movesTaskToBackOnRootBack(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.S
