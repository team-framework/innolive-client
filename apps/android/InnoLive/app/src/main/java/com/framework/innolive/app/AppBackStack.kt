package com.framework.innolive.app

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

