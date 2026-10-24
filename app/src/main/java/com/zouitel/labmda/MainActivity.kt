package com.zouitel.labmda

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.activity.enableEdgeToEdge
import cafe.adriel.voyager.navigator.Navigator
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.mmk.kmpauth.google.GoogleAuthCredentials
import com.mmk.kmpauth.google.GoogleAuthProvider
import com.zouitel.presentaion.common.AppTheme
import com.zouitel.presentaion.screens.account.LogInScreen
import com.zouitel.presentaion.screens.payment.PayPalReturn
import com.zouitel.presentaion.screens.payment.PayPalReturnHandler
import com.zouitel.presentaion.screens.payment.PaymentScreen

class MainActivity : ComponentActivity() {
    private val incomingPaymentReturn = mutableStateOf<PayPalReturn?>(null)
    private val returnHandler = PayPalReturnHandler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        GoogleAuthProvider.create(
            credentials = GoogleAuthCredentials(
                serverId = LogInScreen.WEB_CLIENT_ID
            )
        )

        incomingPaymentReturn.value = returnHandler.parse(intent)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context)
                        .components { add(KtorNetworkFetcherFactory()) }
                        .build()
                }

                val paymentReturn = incomingPaymentReturn.value
                key(paymentReturn) {
                    Navigator(paymentReturn?.let { PaymentScreen(it.orderId) } ?: LogInScreen())
                }
            }
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        incomingPaymentReturn.value = returnHandler.parse(newIntent)
    }
}