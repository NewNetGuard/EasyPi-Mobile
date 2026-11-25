package io.easypi.mobile

import android.content.Context
import io.easypi.mobile.vpn.HomeNetworkDetector

/** Uses the direct EasyPi origin at home and Mobile Link everywhere else. */
object EasyPiEndpointResolver {
    fun resolve(context: Context, link: LinkedEasyPi): LinkedEasyPi {
        val home = link.homeServerUrl ?: return link
        return if (HomeNetworkDetector.isDirectlyReachable(context, link)) {
            link.copy(serverUrl = home)
        } else {
            link
        }
    }
}
