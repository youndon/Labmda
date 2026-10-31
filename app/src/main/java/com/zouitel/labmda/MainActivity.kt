package com.zouitel.labmda

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.zouitel.presentaion.screens.account.AccountPreferencesStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.koin.android.ext.android.inject
import cafe.adriel.voyager.navigator.Navigator
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.mmk.kmpauth.google.GoogleAuthCredentials
import com.mmk.kmpauth.google.GoogleAuthProvider
import com.zouitel.presentaion.common.AppTheme
import com.zouitel.presentaion.screens.account.LogInScreen

class MainActivity : ComponentActivity() {
    private val accountPreferencesStore: AccountPreferencesStore by inject()
    private var darkModeEnabled by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        GoogleAuthProvider.create(
            credentials = GoogleAuthCredentials(
                serverId = LogInScreen.WEB_CLIENT_ID
            )
        )

        enableEdgeToEdge()
        lifecycleScope.launch {
            accountPreferencesStore.preferences.collect { preferences ->
                darkModeEnabled = preferences.darkModeEnabled
                val locales = if (preferences.language == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(preferences.language)
                AppCompatDelegate.setApplicationLocales(locales)
            }
        }
        setContent {
            AppTheme(darkTheme = darkModeEnabled) {
                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context)
                        .components { add(KtorNetworkFetcherFactory()) }
                        .build()
                }

                Navigator(LogInScreen())
            }
        }
    }
}