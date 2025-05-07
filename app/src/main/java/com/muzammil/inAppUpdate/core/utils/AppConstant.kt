package com.muzammil.inAppUpdate.core.utils

import com.google.android.play.core.install.model.AppUpdateType

object AppConstant {

    // you can save type in shared preference or can get from firebase remote config
    var inAppUpdateType = AppUpdateType.IMMEDIATE
}