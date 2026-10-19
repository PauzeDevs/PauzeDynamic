package dev.pauzex.dynamicisland

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.util.Locale

/**
 * PauzeX Pro paywall.
 *
 * Prices shown before Play returns ProductDetails are indicative labels only. Before publishing,
 * configure the matching subscription base plans and lifetime product in Play Console.
 * This client-only implementation checks the Play Billing purchase state; a production launch
 * should additionally verify purchase tokens on a trusted backend.
 */
class ProActivity : Activity() {
    companion object {
        private const val WEEKLY = "pauzex_pro_weekly"
        private const val YEARLY = "pauzex_pro_yearly"
        private const val LIFETIME = "pauzex_pro_lifetime"
        private val SUBSCRIPTION_IDS = setOf(WEEKLY, YEARLY)
        private val ALL_IDS = SUBSCRIPTION_IDS + LIFETIME
    }

    private val prefs by lazy { getSharedPreferences(IslandService.PREFS, MODE_PRIVATE) }
    private val productDetails = mutableMapOf<String, ProductDetails>()
    private val priceViews = mutableMapOf<String, TextView>()
    private lateinit var statusLabel: TextView
    private var billingClient: BillingClient? = null
    private var connected = false
    private var hasOwnedProProduct = false
    private val resolvedPurchaseTypes = mutableSetOf<String>()

    private val background = Color.rgb(7, 8, 12)
    private val panel = Color.rgb(17, 19, 25)
    private val line = Color.rgb(43, 46, 55)
    private val accent = Color.rgb(180, 190, 255)
    private val muted = Color.rgb(158, 162, 174)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, radius: Int = 20, stroke: Int = line) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun label(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        }

    private fun cardButton(value: String, primary: Boolean = false, click: () -> Unit) =
        TextView(this).apply {
            text = value
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (primary) Color.rgb(12, 13, 17) else Color.WHITE)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = shape(if (primary) Color.rgb(245, 246, 250) else Color.rgb(29, 31, 39), 15,
                if (primary) Color.rgb(245, 246, 250) else line)
            setOnClickListener { click() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(26))
            setBackgroundColor(background)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = label("‹", 30f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = shape(panel, 15)
            setOnClickListener { finish() }
        }
        top.addView(back, LinearLayout.LayoutParams(dp(44), dp(42)))
        top.addView(label("PAUZEX  /  PRO", 11f, accent, true).apply {
            letterSpacing = .12f
            setPadding(dp(12), 0, 0, 0)
        })
        root.addView(top)

        root.addView(label("A little more\nextraordinary.", 31f, Color.WHITE, true).apply {
            letterSpacing = -.035f
            setPadding(0, dp(26), 0, dp(10))
        })
        root.addView(label("Refined live activities, deeper customisation, and tools that feel at home on your phone.", 14f, muted).apply {
            setLineSpacing(dp(3), 1f)
        })

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(27, 30, 43), Color.rgb(10, 11, 16))
            ).apply {
                cornerRadius = dp(23).toFloat()
                setStroke(dp(1), Color.rgb(53, 56, 72))
            }
        }
        val pill = TextView(this).apply {
            text = "•  NOW PLAYING                                      Ⅱ"
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = shape(Color.rgb(3, 4, 7), 24, Color.rgb(55, 58, 73))
        }
        hero.addView(pill, LinearLayout.LayoutParams(-1, -2))
        hero.addView(label("One glance. The right details.", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(15), 0, dp(5))
        })
        hero.addView(label("Built for a smoother Android experience.", 11f, muted).apply { gravity = Gravity.CENTER })
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })

        root.addView(label("PRO INCLUDES", 10f, accent, true).apply {
            letterSpacing = .14f
            setPadding(0, dp(23), 0, dp(10))
        })
        listOf(
            "✦  Exclusive dark, glass and colour themes",
            "◷  Custom countdown durations up to 12 hours",
            "⌁  Adjustable low-battery alert threshold",
            "◉  Premium panel layouts and future Pro modules",
            "↻  Restore purchases across supported Play installs"
        ).forEach { feature ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(13), dp(13), dp(13))
                background = shape(panel, 14)
            }
            row.addView(label(feature, 12.5f, Color.rgb(228, 230, 237)), LinearLayout.LayoutParams(-1, -2))
            root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }

        root.addView(label("CHOOSE YOUR PLAN", 10f, accent, true).apply {
            letterSpacing = .14f
            setPadding(0, dp(19), 0, dp(10))
        })

        addPlan(root, WEEKLY, "Weekly", "₹200 / week", "Flexible access · renews weekly", "WEEKLY")
        addPlan(root, YEARLY, "Yearly", "₹250 / year", "Best value · renews yearly", "BEST VALUE")
        addPlan(root, LIFETIME, "Lifetime", "₹400 once", "One-time payment · no renewal", "LIFETIME")

        statusLabel = label("Connecting to Google Play…", 11f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(4))
        }
        root.addView(statusLabel)
        root.addView(cardButton("Restore purchases") { refreshOwnedPurchases() }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        root.addView(label("Purchases are processed by Google Play. Weekly and yearly plans renew according to the terms shown by Google Play. You can manage or cancel subscriptions in Google Play. Prices displayed by Play are authoritative. Product IDs and plan pricing must be configured in Play Console before purchases can be completed.", 10f, Color.rgb(116, 120, 132)).apply {
            setLineSpacing(dp(3), 1f)
            setPadding(dp(2), dp(15), dp(2), 0)
        })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(root, ViewGroup.LayoutParams(-1, -2))
        })
        connectBilling()
    }

    private fun addPlan(parent: LinearLayout, id: String, title: String, fallback: String, description: String, badgeText: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = shape(panel, 18, if (id == YEARLY) Color.rgb(100, 109, 154) else line)
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(label(title, 16f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(label(badgeText, 9f, if (id == YEARLY) accent else muted, true).apply {
            background = shape(Color.rgb(29, 31, 41), 9, Color.rgb(47, 49, 59))
            setPadding(dp(8), dp(5), dp(8), dp(5))
            letterSpacing = .035f
        })
        card.addView(heading)
        card.addView(priceLine(id, fallback), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        card.addView(label(description, 11f, muted).apply { setPadding(0, dp(4), 0, dp(13)) })
        card.addView(cardButton("Continue", primary = id == YEARLY) { purchase(id) })
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun priceLine(id: String, fallback: String): TextView {
        return label(fallback, 24f, Color.WHITE, true).also { priceViews[id] = it }
    }

    private fun connectBilling() {
        val client = BillingClient.newBuilder(applicationContext)
            .setListener { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    purchases.forEach { processPurchase(it) }
                    status("Purchase update received. Refreshing entitlements…")
                    refreshOwnedPurchases()
                } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                    status("Purchase cancelled. No payment was taken.")
                } else {
                    status("Google Play billing: ${billingResult.debugMessage.ifBlank { billingResult.responseCode.toString() }}")
                }
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
        billingClient = client
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    status("Google Play billing isn't available: ${result.debugMessage}")
                    return
                }
                connected = true
                status("Checking plans and restoring purchases…")
                queryProducts(BillingClient.ProductType.SUBS, listOf(WEEKLY, YEARLY))
                queryProducts(BillingClient.ProductType.INAPP, listOf(LIFETIME))
                refreshOwnedPurchases()
            }

            override fun onBillingServiceDisconnected() {
                connected = false
                status("Google Play disconnected. Try again when it reconnects.")
            }
        })
    }

    private fun queryProducts(type: String, ids: List<String>) {
        val products = ids.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(type)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        billingClient?.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                queryResult.productDetailsList.forEach { details ->
                    productDetails[details.productId] = details
                    val displayedPrice = if (details.productType == BillingClient.ProductType.SUBS) {
                        details.subscriptionOfferDetails?.firstOrNull()
                            ?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
                    } else {
                        details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
                            ?: details.oneTimePurchaseOfferDetails?.formattedPrice
                    }
                    if (!displayedPrice.isNullOrBlank()) {
                        priceViews[details.productId]?.text = when (details.productId) {
                            WEEKLY -> "$displayedPrice / week"
                            YEARLY -> "$displayedPrice / year"
                            else -> "$displayedPrice once"
                        }
                    }
                }
                if (ids.any { it !in productDetails }) {
                    status("Some plans aren't published yet. Configure their product IDs and base plans in Play Console.")
                } else {
                    status(if (prefs.getBoolean("pro_unlocked", false)) "PauzeX Pro is active on this Play account." else "Plans loaded from Google Play.")
                }
            } else {
                status("Plans unavailable from Google Play. Check Play Console products and connection.")
            }
        }
    }

    private fun purchase(id: String) {
        val client = billingClient
        val details = productDetails[id]
        if (!connected || client == null || details == null) {
            val expectedId = id
            AlertDialog.Builder(this)
                .setTitle("Plan not available yet")
                .setMessage("Google Play did not return the product “$expectedId”. Create and activate this product in Play Console, attach a base plan for subscriptions, and test using a Play-installed build and a license tester.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
        val offerToken = if (details.productType == BillingClient.ProductType.SUBS) {
            details.subscriptionOfferDetails?.firstOrNull()?.offerToken
        } else {
            details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
        }
        if (!offerToken.isNullOrBlank()) params.setOfferToken(offerToken)
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(params.build()))
            .build()
        val result = client.launchBillingFlow(this, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            status("Could not open Google Play: ${result.debugMessage}")
        } else {
            status("Complete the secure payment sheet from Google Play.")
        }
    }

    private fun refreshOwnedPurchases() {
        val client = billingClient
        if (client == null || !connected) {
            status("Connect to Google Play to restore purchases.")
            return
        }
        resolvedPurchaseTypes.clear()
        hasOwnedProProduct = false
        queryPurchases(client, BillingClient.ProductType.SUBS)
        queryPurchases(client, BillingClient.ProductType.INAPP)
    }

    private fun queryPurchases(client: BillingClient, type: String) {
        val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    .forEach { processPurchase(it) }
                if (purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.any { product -> product in ALL_IDS } }) {
                    hasOwnedProProduct = true
                }
                resolvedPurchaseTypes.add(type)
                if (resolvedPurchaseTypes.containsAll(setOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP))) {
                    prefs.edit().putBoolean("pro_unlocked", hasOwnedProProduct).apply()
                    applyProState(hasOwnedProProduct)
                }
            } else {
                status("Restore couldn't be completed. Try again when Google Play is available.")
            }
        }
    }

    private fun processPurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            status("Payment is pending. Pro will unlock once Google Play confirms payment.")
            return
        }
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED || purchase.products.none { it in ALL_IDS }) return

        hasOwnedProProduct = true
        prefs.edit().putBoolean("pro_unlocked", true).apply()
        applyProState(true)
        status("PauzeX Pro purchase confirmed by Google Play.")

        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient?.acknowledgePurchase(params) { result ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    status("Purchase received, but Google Play acknowledgement needs retry.")
                }
            }
        }
    }

    private fun applyProState(unlocked: Boolean) {
        if (unlocked) {
            status("PauzeX Pro is active.")
        } else {
            status("No active PauzeX Pro purchase found for this Google Play account.")
        }
    }

    private fun status(message: String) {
        if (::statusLabel.isInitialized) runOnUiThread { statusLabel.text = message }
    }

    override fun onResume() {
        super.onResume()
        if (connected) refreshOwnedPurchases()
    }

    override fun onDestroy() {
        billingClient?.endConnection()
        billingClient = null
        super.onDestroy()
    }
}
