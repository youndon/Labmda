package com.zouitel.presentaion.screens.wallet

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class WalletUiState(val summary:WalletSummary=WalletSummary(),val isLoading:Boolean=true,val isSubmitting:Boolean=false,val error:String?=null,val message:String?=null)
class WalletScreenModel(private val repository:WalletRepository):ScreenModel {
    private val _state=MutableStateFlow(WalletUiState()); val state:StateFlow<WalletUiState> = _state.asStateFlow()
    private var payoutRequestKey:String?=null
    init { refresh() }
    fun refresh(){screenModelScope.launch{_state.value=_state.value.copy(isLoading=true,error=null);runCatching{repository.loadWallet()}.onSuccess{_state.value=_state.value.copy(summary=it,isLoading=false)}.onFailure{_state.value=_state.value.copy(isLoading=false,error=it.message ?: "Could not load wallet.")}}}
    fun requestPayout(){
        if(_state.value.isSubmitting)return
        if(payoutRequestKey==null)payoutRequestKey=UUID.randomUUID().toString()
        val key=payoutRequestKey ?: return
        screenModelScope.launch{
            _state.value=_state.value.copy(isSubmitting=true,error=null,message=null)
            runCatching{repository.requestPayout(key)}.onSuccess{p->
                _state.value=_state.value.copy(isSubmitting=false,message="Payout request "+p.id.takeLast(8)+" submitted: "+p.status.replace('_',' ')+".")
                if(p.status.uppercase() in setOf("SUCCEEDED","FAILED","REJECTED","CANCELLED","EXPIRED"))payoutRequestKey=null
                refresh()
            }.onFailure{_state.value=_state.value.copy(isSubmitting=false,error=it.message ?: "Payout request failed; retry will reuse the same request key.")}
        }
    }
}
data class PayoutAccountUiState(val account:PayoutAccountSummary=PayoutAccountSummary(),val isLoading:Boolean=true,val isSaving:Boolean=false,val error:String?=null,val message:String?=null)
class PayoutAccountScreenModel(private val repository:WalletRepository):ScreenModel {
    private val _state=MutableStateFlow(PayoutAccountUiState());val state:StateFlow<PayoutAccountUiState> = _state.asStateFlow()
    init{refresh()}
    fun refresh(){screenModelScope.launch{_state.value=_state.value.copy(isLoading=true,error=null);runCatching{repository.loadPayoutAccount()}.onSuccess{_state.value=_state.value.copy(account=it,isLoading=false)}.onFailure{_state.value=_state.value.copy(isLoading=false,error=it.message ?: "Could not load payout account.")}}}
    fun save(g:String,s:String,h:String,a:String,m:String,street:String,city:String,province:String,postal:String){
        if(_state.value.isSaving)return
        screenModelScope.launch{_state.value=_state.value.copy(isSaving=true,error=null,message=null);runCatching{repository.saveGcashPayoutAccount(g,s,h,a,m,street,city,province,postal)}.onSuccess{_state.value=_state.value.copy(account=it,isSaving=false,message="Payout account submitted for administrator review.")}.onFailure{_state.value=_state.value.copy(isSaving=false,error=it.message ?: "Could not save payout account.")}}
    }
}
data class AdminPaymentsUiState(val dashboard:AdminPaymentsDashboard=AdminPaymentsDashboard(),val isLoading:Boolean=true,val isSubmitting:Boolean=false,val error:String?=null,val message:String?=null)
class AdminPaymentsScreenModel(private val repository:WalletRepository):ScreenModel {
    private val _state=MutableStateFlow(AdminPaymentsUiState());val state:StateFlow<AdminPaymentsUiState> = _state.asStateFlow()
    init{refresh()}
    fun refresh(){screenModelScope.launch{_state.value=_state.value.copy(isLoading=true,error=null);runCatching{repository.loadAdminDashboard()}.onSuccess{_state.value=_state.value.copy(dashboard=it,isLoading=false)}.onFailure{_state.value=_state.value.copy(isLoading=false,error=it.message ?: "Could not load admin payment data.")}}}
    fun review(uid:String,approved:Boolean,note:String){if(_state.value.isSubmitting)return;screenModelScope.launch{_state.value=_state.value.copy(isSubmitting=true,error=null,message=null);runCatching{repository.reviewPayoutAccount(uid,approved,note)}.onSuccess{_state.value=_state.value.copy(isSubmitting=false,message="Payout account review saved.");refresh()}.onFailure{_state.value=_state.value.copy(isSubmitting=false,error=it.message ?: "Review failed.")}}}
    fun approvePayoutRequest(id:String){if(_state.value.isSubmitting)return;screenModelScope.launch{_state.value=_state.value.copy(isSubmitting=true,error=null,message=null);runCatching{repository.approvePayoutRequest(id)}.onSuccess{_state.value=_state.value.copy(isSubmitting=false,message="Payout approved and submitted to Xendit.");refresh()}.onFailure{_state.value=_state.value.copy(isSubmitting=false,error=it.message ?: "Payout approval failed. Refresh and inspect the request before retrying.")}}}
    fun rejectPayoutRequest(id:String,note:String){if(_state.value.isSubmitting)return;screenModelScope.launch{_state.value=_state.value.copy(isSubmitting=true,error=null,message=null);runCatching{repository.rejectPayoutRequest(id,note)}.onSuccess{_state.value=_state.value.copy(isSubmitting=false,message="Payout request rejected.");refresh()}.onFailure{_state.value=_state.value.copy(isSubmitting=false,error=it.message ?: "Payout rejection failed.")}}}
    fun refund(type:String,id:String){if(_state.value.isSubmitting)return;screenModelScope.launch{_state.value=_state.value.copy(isSubmitting=true,error=null,message=null);runCatching{repository.refundPayment(type,id,UUID.randomUUID().toString())}.onSuccess{_state.value=_state.value.copy(isSubmitting=false,message="Refund request submitted.");refresh()}.onFailure{_state.value=_state.value.copy(isSubmitting=false,error=it.message ?: "Refund failed.")}}}
}
