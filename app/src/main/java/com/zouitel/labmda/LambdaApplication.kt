package com.zouitel.labmda

import android.app.Application
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.mmk.kmpauth.core.KMPAuth
import com.mmk.kmpauth.google.google
import com.zouitel.presentaion.di.presentationModule
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.initialize
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androix.startup.KoinStartup
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.logger.Level
import org.koin.dsl.KoinConfiguration

@OptIn(KoinExperimentalAPI::class)
class LambdaApplication : Application(), KoinStartup, SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        KMPAuth.initialize {
            google("814762435029-63a565o3ub7d4ph32es0vugn5aoa79qs.apps.googleusercontent.com")
        }
        Firebase.initialize(this)
        val appCheck = FirebaseAppCheck.getInstance()
        if (BuildConfig.DEBUG) {
            appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        } else {
            appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        }
    }

    // This configures the global ImageLoader and Caching behavior for Coil 3
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    // Use up to 25% of available app memory for image caching
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    // Save cached images to internal disk storage
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(1024 * 1024 * 100) // 100 MB max disk cache size
                    .build()
            }
            .crossfade(true)
            .logger(DebugLogger()) // Optional: logs image loading status in Logcat
            .build()
    }

    override fun onKoinStartup() = KoinConfiguration {
        androidLogger(Level.INFO)
        androidContext(this@LambdaApplication)
        modules(presentationModule)
    }
}