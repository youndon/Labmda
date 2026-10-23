package com.zouitel.presentaion.screens.payment

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.zouitel.presentaion.model.PayPalSession

/** Opens PayPal's server-created approval URL in the browser or installed PayPal handler. */
class PayPalPaymentLauncher {
    fun launch(context: Context, session: PayPalSession) {
        val uri = Uri.parse(session.checkoutUrl)
        require(uri.scheme == "https") { "PayPal approval must use HTTPS." }
        require(uri.host != null) { "PayPal approval URL is invalid." }
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}