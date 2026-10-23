package com.zouitel.presentaion.screens.account

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.model.PaymentMethodType
import com.zouitel.presentaion.model.UserPaymentMethod
import dev.gitlive.firebase.auth.FirebaseAuth
import dev.gitlive.firebase.database.DatabaseReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class PaymentMethodsScreenModel(
    private val auth: FirebaseAuth,
    private val db: DatabaseReference
) : ScreenModel {

    private val _paymentMethods = MutableStateFlow<List<UserPaymentMethod>>(emptyList())
    val paymentMethods: StateFlow<List<UserPaymentMethod>> = _paymentMethods.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        loadPaymentMethods()
    }

    private fun loadPaymentMethods() {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                db.child("users").child(uid).child("paymentMethods")
                    .valueEvents
                    .map { snapshot ->
                        if (snapshot.exists) {
                            snapshot.children.mapNotNull { child ->
                                runCatching { child.value<UserPaymentMethod>() }.getOrNull()
                            }
                        } else {
                            emptyList()
                        }
                    }
                    .catch { emit(emptyList()) }
                    .collect { methods ->
                        _paymentMethods.value = methods.sortedByDescending { it.createdAtTimestamp }
                        _isLoading.value = false
                    }
            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage
                _isLoading.value = false
            }
        }
    }

    fun addStripeCard(
        cardNumber: String,
        cardHolderName: String,
        expiryMonth: String,
        expiryYear: String,
        cvc: String,
        onComplete: (Boolean) -> Unit
    ) {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrEmpty()) {
            _errorMessage.value = "User not signed in"
            onComplete(false)
            return
        }

        if (cardNumber.length < 4) {
            _errorMessage.value = "Invalid card number"
            onComplete(false)
            return
        }

        screenModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val methodId = UUID.randomUUID().toString()
                val last4 = cardNumber.takeLast(4)
                val isFirst = _paymentMethods.value.isEmpty()

                val paymentMethod = UserPaymentMethod(
                    id = methodId,
                    userId = uid,
                    type = PaymentMethodType.STRIPE_CARD,
                    title = "•••• •••• •••• $last4",
                    subtitle = "Expires $expiryMonth/$expiryYear",
                    identifier = last4,
                    cardHolderName = cardHolderName,
                    expiryMonth = expiryMonth,
                    expiryYear = expiryYear,
                    isDefault = isFirst,
                    createdAtTimestamp = System.currentTimeMillis()
                )

                db.child("users").child(uid).child("paymentMethods").child(methodId)
                    .setValue(paymentMethod)

                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                    onComplete(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _errorMessage.value = e.localizedMessage
                    _isLoading.value = false
                    onComplete(false)
                }
            }
        }
    }

    fun linkPayPalAccount(
        email: String,
        onComplete: (Boolean) -> Unit
    ) {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrEmpty()) {
            _errorMessage.value = "User not signed in"
            onComplete(false)
            return
        }

        if (!email.contains("@")) {
            _errorMessage.value = "Invalid PayPal email"
            onComplete(false)
            return
        }

        screenModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val methodId = UUID.randomUUID().toString()
                val isFirst = _paymentMethods.value.isEmpty()

                val paymentMethod = UserPaymentMethod(
                    id = methodId,
                    userId = uid,
                    type = PaymentMethodType.PAYPAL,
                    title = email,
                    subtitle = "PayPal Account",
                    identifier = email,
                    isDefault = isFirst,
                    createdAtTimestamp = System.currentTimeMillis()
                )

                db.child("users").child(uid).child("paymentMethods").child(methodId)
                    .setValue(paymentMethod)

                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                    onComplete(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _errorMessage.value = e.localizedMessage
                    _isLoading.value = false
                    onComplete(false)
                }
            }
        }
    }

    fun setDefaultPaymentMethod(methodId: String) {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch(Dispatchers.IO) {
            try {
                val methods = _paymentMethods.value
                for (method in methods) {
                    val isDefault = (method.id == methodId)
                    db.child("users").child(uid).child("paymentMethods").child(method.id)
                        .child("isDefault").setValue(isDefault)
                }
            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage
            }
        }
    }

    fun deletePaymentMethod(methodId: String) {
        val uid = auth.currentUser?.uid ?: return
        screenModelScope.launch(Dispatchers.IO) {
            try {
                db.child("users").child(uid).child("paymentMethods").child(methodId).removeValue()
            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage
            }
        }
    }
}
