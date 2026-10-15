package com.zouitel.labmda

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cafe.adriel.voyager.navigator.Navigator
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.mmk.kmpauth.google.GoogleAuthCredentials
import com.mmk.kmpauth.google.GoogleAuthProvider
import com.zouitel.presentaion.screens.account.LogInScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        GoogleAuthProvider.create(
            credentials = GoogleAuthCredentials(
                serverId = LogInScreen.WEB_CLIENT_ID
            )
        )

        enableEdgeToEdge()
        setContent {

            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context)
                    .components { add(KtorNetworkFetcherFactory()) }
                    .build()
            }

            Navigator(LogInScreen())
        }
    }
}