package com.zouitel.presentaion.notifications

import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.functions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Displays payment/order push messages while the app is in the foreground and
 * re-registers rotated FCM tokens for the currently authenticated account.
 */
class LabmdaMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "Labmda update"
        val body = message.notification?.body ?: message.data["body"] ?: "Your payment or order status has changed."
        val notification = NotificationCompat.Builder(this, "order_status_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(
                (System.currentTimeMillis() and 0x7fffffff).toInt(),
                notification
            )
        }
    }

    override fun onNewToken(token: String) {
        if (FirebaseAuth.getInstance().currentUser == null) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                Firebase.functions.httpsCallable("registerFcmToken")
                    .invoke(mapOf("token" to token))
            }
        }
    }
}
