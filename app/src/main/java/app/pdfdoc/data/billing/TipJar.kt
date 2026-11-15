package app.pdfdoc.data.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** One tip size as Google Play sells it, with the price in the user's currency. */
data class Tip(val id: String, val price: String, internal val details: ProductDetails)

sealed interface TipJarState {
    data object Loading : TipJarState
    /** No Play Store, no account, or the tips are not set up in Play Console yet. */
    data object Unavailable : TipJarState
    data class Ready(val tips: List<Tip>) : TipJarState
}

enum class TipEvent { THANKS, PENDING, FAILED }

/**
 * Optional tips to the developer through Google Play Billing. Tips are consumable in-app products,
 * so the same tip can be given again; they unlock nothing. Talks to the Play Store app on the device only.
 */
@Singleton
class TipJar @Inject constructor(@ApplicationContext context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<TipJarState>(TipJarState.Loading)
    val state: StateFlow<TipJarState> = _state.asStateFlow()
    private val _events = Channel<TipEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val client = BillingClient.newBuilder(context)
        .setListener { result, purchases -> onPurchases(result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    /** Connects and loads the prices; safe to call again, for example when the settings screen opens. */
    fun load() {
        if (client.isReady) {
            scope.launch { refresh() }
            return
        }
        _state.value = TipJarState.Loading
        runCatching {
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) scope.launch { refresh() }
                    else _state.value = TipJarState.Unavailable
                }

                override fun onBillingServiceDisconnected() = Unit
            })
        }.onFailure { _state.value = TipJarState.Unavailable }
    }

    fun give(activity: Activity, tip: Tip) {
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(tip.details).build()))
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK &&
            result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED
        ) {
            _events.trySend(TipEvent.FAILED)
        }
    }

    private suspend fun refresh() {
        val products = PRODUCT_IDS.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
        }
        val result = client.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(products).build())
        val tips = result.productDetailsList.orEmpty()
            .mapNotNull { d -> d.oneTimePurchaseOfferDetails?.let { Tip(d.productId, it.formattedPrice, d) to it.priceAmountMicros } }
            .sortedBy { it.second }
            .map { it.first }
        _state.value = if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK && tips.isNotEmpty()) {
            TipJarState.Ready(tips)
        } else {
            TipJarState.Unavailable
        }
        // Tips paid earlier but not yet consumed (app closed during the purchase, pending payment finished).
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build())
            .purchasesList.forEach { consume(it, thank = false) }
    }

    private fun onPurchases(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach { p ->
                if (p.purchaseState == Purchase.PurchaseState.PENDING) _events.trySend(TipEvent.PENDING)
                else scope.launch { consume(p, thank = true) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> _events.trySend(TipEvent.FAILED)
        }
    }

    private suspend fun consume(purchase: Purchase, thank: Boolean) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val result = client.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build())
        if (thank && result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) _events.trySend(TipEvent.THANKS)
    }

    companion object {
        /** In-app products to create in Play Console (Monetize › Products › In-app products), consumable. */
        val PRODUCT_IDS = listOf("tip_small", "tip_medium", "tip_large")
    }
}
