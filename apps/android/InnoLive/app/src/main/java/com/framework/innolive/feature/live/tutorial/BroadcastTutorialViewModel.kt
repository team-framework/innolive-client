package com.framework.innolive.feature.live.tutorial

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** 화면 회전으로 Activity가 다시 만들어져도 진행 중인 안내 단계를 이어서 보여 준다. */
class BroadcastTutorialViewModel(application: Application) : AndroidViewModel(application) {
    val coordinator = BroadcastTutorialCoordinator(BroadcastTutorialPreferences(application))
}
