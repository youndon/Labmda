package com.zouitel.presentaion.screens.payment

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.zouitel.presentaion.model.StripeSession

/** Opens the server-created Stripe hosted Checkout page; no secret key belongs in the app. */
class StripePaymentLauncher {
    fun launch(context: Context, session: StripeSession) {
        val uri = Uri.parse(session.checkoutUrl)
        require(uri.scheme == "https") { "Stripe checkout must use HTTPS." }
        require(uri.host != null) { "Stripe checkout URL is invalid." }
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}