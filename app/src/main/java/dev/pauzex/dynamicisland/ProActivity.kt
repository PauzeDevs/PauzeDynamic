package dev.pauzex.dynamicisland

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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

private data class ProPlan(
    val id: String,
    val title: String,
    val priceFallback: String,
    val term: String,
    val detail: String,
    val productType: String,
    val basePlanId: String? = null,
    val featured: Boolean = false
)

class ProActivity : Activity() {
    companion object {
        private const val WEEKLY_ID = "pauzex_pro_weekly"
        private const val YEARLY_ID = "pauzex_pro_yearly"
        private const val LIFETIME_ID = "pauzex_pro_lifetime"
        private const val WEEKLY_BASE_PLAN = "weekly"
        private const val YEARLY_BASE_PLAN = "yearly"

        private val PLANS = listOf(
            ProPlan(
                WEEKLY_ID, "1 week", "₹200", "/ week",
                "Weekly auto-renewing subscription",
                BillingClient.ProductType.SUBS, WEEKLY_BASE_PLAN
            ),
            ProPlan(
                YEARLY_ID, "1 year", "₹250", "/ year",
                "Yearly auto-renewing subscription · best value",
                BillingClient.ProductType.SUBS, YEARLY_BASE_PLAN, featured = true
            ),
            ProPlan(
                LIFETIME_ID, "Lifetime", "₹400", " once",
                "One payment · no recurring subscription",
                BillingClient.ProductType.INAPP
            )
        )
    }

    private val bg = Color.rgb(5, 6, 8)
    private val surface = Color.rgb(16, 17, 20)
    private val stroke = Color.rgb(42, 44, 49)
    private val textPrimary = Color.rgb(245, 245, 247)
    private val textSecondary = Color.rgb(157, 159, 166)
    private val proAccent = Color.rgb(151, 166, 255)

    private lateinit var billingClient: BillingClient
    private lateinit var statusLabel: TextView
    private lateinit var buyButton: TextView
    private lateinit var entitlementLabel: TextView
    private val productDetails = mutableMapOf<String, ProductDetails>()
    private val priceLabels = mutableMapOf<String, TextView>()
    private val planCards = mutableMapOf<String, LinearLayout>()
    private var selectedPlanId = YEARLY_ID
    private var connected = false

    private val prefs by lazy {
        getSharedPreferences(IslandService.PREFS, MODE_PRIVATE)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radiusDp: Int, border: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (border != Color.TRANSPARENT) setStroke(dp(1), border)
        }

    private fun text(value: String, size: Float, color: Int = textPrimary, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = if (size >= 22f) -0.025f else if (size <= 11f) 0.015f else 0f
            setPadding(0, dp(3), 0, dp(3))
            setLineSpacing(dp(1).toFloat(), 1f)
        }

    private fun addGap(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun pillLabel(value: String, foreground: Int, fill: Int) = TextView(this).apply {
        text = value
        textSize = 10f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.06f
        setTextColor(foreground)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = rounded(fill, 50)
    }

    private fun createPlanCard(plan: ProPlan): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.rgb(67, 69, 76)),
                rounded(surface, 20, stroke),
                null
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                selectedPlanId = plan.id
                refreshPlanSelection()
            }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val radio = TextView(this).apply {
            text = "○"
            textSize = 22f
            setTextColor(proAccent)
            gravity = Gravity.CENTER
        }
        top.addView(radio, LinearLayout.LayoutParams(dp(30), dp(30)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text(plan.title, 15f, textPrimary, true))
        names.addView(text(plan.detail, 10.5f, textSecondary))
        top.addView(names, LinearLayout.LayoutParams(0, -2, 1f).apply {
            rightMargin = dp(8)
        })
        val price = TextView(this).apply {
            text = plan.priceFallback + plan.term
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 2
        }
        top.addView(price, LinearLayout.LayoutParams(dp(108), -2))
        card.addView(top)
        if (plan.featured) {
            card.addView(pillLabel("BEST VALUE", Color.rgb(8, 9, 13), proAccent),
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(30); topMargin = dp(6) })
        }
        planCards[plan.id] = card
        priceLabels[plan.id] = price
        card.tag = radio
        return card
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildScreen()
        setupBilling()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        SystemBarInsets.apply(root)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(22))
            setBackgroundColor(bg)
        }

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        nav.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        nav.addView(text("PAUZEX  /  MEMBERSHIP", 10f, textSecondary, true).apply {
            letterSpacing = 0.075f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(pillLabel("PRO", Color.rgb(9, 10, 14), proAccent))
        content.addView(nav)

        addGap(content, 17)
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(26), dp(20), dp(25))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(18, 19, 23), Color.rgb(8, 9, 11))
            ).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.rgb(43, 45, 51))
            }
        }
        val glyph = TextView(this).apply {
            text = "✦"
            textSize = 28f
            setTextColor(proAccent)
            gravity = Gravity.CENTER
        }
        hero.addView(glyph)
        hero.addView(text("More island.\nMore control.", 27f, textPrimary, true).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setLineSpacing(0f, 0.98f)
            setPadding(0, dp(9), 0, dp(7))
        })
        hero.addView(text("A refined live-activity experience, built around your phone.", 13f, textSecondary).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(dp(6), 0, dp(6), 0)
        })
        content.addView(hero, LinearLayout.LayoutParams(-1, -2))

        addGap(content, 20)
        content.addView(text("WHAT'S INCLUDED", 10f, proAccent, true).apply {
            letterSpacing = 0.09f
        })
        addGap(content, 8)
        val features = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(4), dp(15), dp(4))
            background = rounded(surface, 20, stroke)
        }
        val perks = listOf(
            "Premium accent palettes and island themes",
            "Subtle playback pulse and live indicators",
            "Music, Alerts and Tools with swipe navigation",
            "Album artwork and supported media controls",
            "Notification actions, inline replies and app filters",
            "Custom countdowns up to 12 hours",
            "Adjustable low-battery warning threshold"
        )
        perks.forEachIndexed { index, perk ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(11), 0, dp(11))
            }
            row.addView(text("✓", 14f, Color.rgb(130, 219, 190), true),
                LinearLayout.LayoutParams(dp(27), -2))
            row.addView(text(perk, 12.5f, textPrimary), LinearLayout.LayoutParams(0, -2, 1f))
            features.addView(row)
            if (index < perks.lastIndex) {
                features.addView(View(this).apply {
                    setBackgroundColor(Color.rgb(36, 38, 43))
                }, LinearLayout.LayoutParams(-1, dp(1)).apply {
                    leftMargin = dp(27)
                })
            }
        }
        content.addView(features)

        addGap(content, 23)
        content.addView(text("Choose your plan", 21f, textPrimary, true).apply {
            letterSpacing = -0.02f
        })
        content.addView(text("One clear price. No surprises before checkout.", 12f, textSecondary))
        addGap(content, 10)
        addGap(content, 8)
        PLANS.forEachIndexed { index, plan ->
            content.addView(createPlanCard(plan), LinearLayout.LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = dp(8)
            })
        }

        addGap(content, 12)
        statusLabel = text("Connecting to Google Play…", 11f, textSecondary).apply {
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(Color.rgb(12, 13, 16), 12, Color.rgb(35, 37, 42))
        }
        content.addView(statusLabel)

        entitlementLabel = text("", 11f, Color.rgb(132, 221, 191), true).apply {
            setPadding(0, dp(7), 0, dp(3))
        }
        content.addView(entitlementLabel)

        content.addView(text(
            "Weekly and yearly plans renew automatically until canceled. Lifetime is a one-time purchase. " +
                "Final localized prices and billing terms are confirmed by Google Play before purchase.",
            10f, textSecondary
        ).apply { setPadding(0, dp(10), 0, 0) })

        content.addView(text(
            "Purchases are only available after the matching products and base plans are configured in Google Play Console. " +
                "If a product is missing, this build will not pretend a purchase succeeded.",
            10f, Color.rgb(130, 133, 143)
        ).apply { setPadding(0, dp(8), 0, dp(6)) })

        content.addView(text("Restore purchases", 12f, proAccent, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { if (connected) queryOwnedPurchases(showMessage = true) else statusLabel.text = "Google Play billing is not connected." }
        })
        content.addView(text("Manage subscription in Google Play", 12f, proAccent, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
            setOnClickListener { openPlaySubscriptions() }
        })

        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        buyButton = TextView(this).apply {
            text = "Continue with Pro"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(7, 8, 11))
            minHeight = dp(54)
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.rgb(190, 192, 199)),
                rounded(Color.rgb(242, 243, 247), 18),
                null
            )
            setOnClickListener { startPurchase() }
        }
        root.addView(buyButton, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(22), dp(8), dp(22), dp(16))
        })
        setContentView(root)
        refreshPlanSelection()
        refreshEntitlementLabel()
    }

    private fun setupBilling() {
        try {
        billingClient = BillingClient.newBuilder(applicationContext)
            .setListener { result, purchases ->
                when (result.responseCode) {
                    BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach { handlePurchase(it) }
                    BillingClient.BillingResponseCode.USER_CANCELED -> statusLabel.text = "Purchase canceled. No payment was made."
                    else -> statusLabel.text = "Google Play returned: ${result.debugMessage.ifBlank { "billing error" }}"
                }
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()

        statusLabel.text = "Connecting to Google Play…"
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                runOnUiThread {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        connected = true
                        statusLabel.text = "Google Play connected. Checking available plans…"
                        queryProductCatalog()
                        queryOwnedPurchases(showMessage = false)
                    } else {
                        connected = false
                        statusLabel.text = "Billing unavailable: ${result.debugMessage.ifBlank { "try again later" }}"
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                runOnUiThread {
                    connected = false
                    statusLabel.text = "Google Play disconnected. Reopen this screen to retry."
                }
            }
        })
        } catch (error: Exception) {
            connected = false
            statusLabel.text = "Google Play billing couldn't start: " +
                (error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
            android.util.Log.e("PauzeXPro", "Unable to initialise Google Play Billing", error)
        }
    }

    private fun queryProductCatalog() {
        val requested = PLANS.map { plan ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(plan.id)
                .setProductType(plan.productType)
                .build()
        }
        val query = QueryProductDetailsParams.newBuilder().setProductList(requested).build()
        try {
            billingClient.queryProductDetailsAsync(query) { result, response ->
                runOnUiThread {
                    try {
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            response.productDetailsList.forEach { productDetails[it.productId] = it }
                            updatePriceLabels()
                            refreshPlanSelection()
                            val missing = PLANS.filter { plan ->
                                val details = productDetails[plan.id]
                                when (plan.productType) {
                                    BillingClient.ProductType.SUBS -> matchingSubscriptionOffer(plan, details) == null
                                    else -> details == null
                                }
                            }
                            statusLabel.text = if (missing.isEmpty()) {
                                "Plans and expected billing periods loaded from Google Play."
                            } else {
                                "Missing product or expected base plan: " + missing.joinToString { it.title } +
                                    ". Check the product IDs and base plan periods in Play Console."
                            }
                        } else {
                            statusLabel.text = "Couldn't load Play prices: ${result.debugMessage.ifBlank { "try again" }}"
                        }
                    } catch (error: Exception) {
                        statusLabel.text = "Couldn't read Google Play plans: " +
                            (error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
                        android.util.Log.e("PauzeXPro", "Unable to process product catalogue response", error)
                    }
                }
            }
        } catch (error: Exception) {
            statusLabel.text = "Couldn't query Google Play plans: " +
                (error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
            android.util.Log.e("PauzeXPro", "Unable to query product catalogue", error)
        }
    }

    private fun updatePriceLabels() {
        PLANS.forEach { plan ->
            val details = productDetails[plan.id]
            val price = when (plan.productType) {
                BillingClient.ProductType.SUBS -> {
                    val offer = matchingSubscriptionOffer(plan, details)
                    val formatted = offer?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
                    if (formatted != null) "$formatted${plan.term}" else "${plan.priceFallback}${plan.term}"
                }
                else -> {
                    val offer = details?.oneTimePurchaseOfferDetailsList?.firstOrNull()
                    val formatted = offer?.formattedPrice ?: details?.oneTimePurchaseOfferDetails?.formattedPrice
                    if (formatted != null) "$formatted${plan.term}" else "${plan.priceFallback}${plan.term}"
                }
            }
            priceLabels[plan.id]?.text = price
        }
    }

    private fun matchingSubscriptionOffer(plan: ProPlan, details: ProductDetails?): ProductDetails.SubscriptionOfferDetails? {
        val offers = details?.subscriptionOfferDetails.orEmpty()
        return offers.firstOrNull { it.basePlanId == plan.basePlanId }
    }

    private fun refreshPlanSelection() {
        PLANS.forEach { plan ->
            val selected = selectedPlanId == plan.id
            planCards[plan.id]?.background = rounded(
                if (selected) Color.rgb(28, 30, 41) else surface,
                18,
                if (selected) proAccent else stroke
            )
            (planCards[plan.id]?.tag as? TextView)?.apply {
                text = if (selected) "◉" else "○"
                setTextColor(if (selected) proAccent else textSecondary)
            }
        }
        val owned = prefs.getBoolean("pro_entitled", false)
        buyButton.text = if (owned) "Pro is active" else "Continue with Pro"
        buyButton.isEnabled = !owned
        buyButton.alpha = if (owned) 0.65f else 1f
    }

    private fun startPurchase() {
        if (prefs.getBoolean("pro_entitled", false)) {
            statusLabel.text = "Pro is already active on this device."
            return
        }
        if (!connected) {
            showBillingSetupInfo()
            return
        }
        val plan = PLANS.first { it.id == selectedPlanId }
        val details = productDetails[plan.id]
        if (details == null) {
            showBillingSetupInfo()
            return
        }

        val paramsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
        if (plan.productType == BillingClient.ProductType.SUBS) {
            val offer = matchingSubscriptionOffer(plan, details)
            if (offer == null) {
                showBillingSetupInfo()
                return
            }
            paramsBuilder.setOfferToken(offer.offerToken)
        } else {
            details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken?.let { token -> paramsBuilder.setOfferToken(token) }
        }

        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(paramsBuilder.build()))
            .build()
        try {
            val result = billingClient.launchBillingFlow(this, flow)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                statusLabel.text = "Purchase could not start: ${result.debugMessage.ifBlank { "Google Play error" }}"
            } else {
                statusLabel.text = "Complete the purchase in Google Play."
            }
        } catch (error: Exception) {
            statusLabel.text = "Google Play couldn't open checkout: " +
                (error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
            android.util.Log.e("PauzeXPro", "Unable to launch checkout", error)
        }
    }

    private fun showBillingSetupInfo() {
        AlertDialog.Builder(this)
            .setTitle("Pro purchases aren't configured yet")
            .setMessage(
                "The plan cards are ready, but this app's Google Play products must be created and activated before checkout can work. " +
                    "Configure these product IDs in Play Console: pauzex_pro_weekly (subscription, weekly base plan), " +
                    "pauzex_pro_yearly (subscription, yearly base plan), and pauzex_pro_lifetime (one-time product). " +
                    "Upload a signed app to an internal testing track and set the prices to match the plans shown here."
            )
            .setPositiveButton("OK", null)
            .show()
        statusLabel.text = "Waiting for active Google Play products."
    }

    private fun queryOwnedPurchases(showMessage: Boolean) {
        if (!connected) {
            if (showMessage) statusLabel.text = "Google Play billing is not connected."
            return
        }
        val ownedIds = mutableSetOf<String>()
        var callbacks = 0
        var anyFailure = false

        fun completeCheck(result: BillingResult, purchases: List<Purchase>) {
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                anyFailure = true
            } else {
                purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    .forEach { ownedIds.addAll(it.products.filter { id -> PLANS.any { plan -> plan.id == id } }) }
                purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    .forEach { handlePurchase(it) }
            }
            callbacks += 1
            if (callbacks == 2) {
                runOnUiThread {
                    if (!anyFailure) {
                        prefs.edit()
                            .putBoolean("pro_entitled", ownedIds.isNotEmpty())
                            .putString("pro_plan", ownedIds.firstOrNull().orEmpty())
                            .apply()
                        refreshEntitlementLabel()
                        refreshPlanSelection()
                        if (showMessage) {
                            statusLabel.text = if (ownedIds.isNotEmpty()) "Purchase restored." else "No active Pro purchase found for this Google account."
                        }
                    } else if (showMessage) {
                        statusLabel.text = "Couldn't verify every purchase type. Try again when online."
                    }
                }
            }
        }

        try {
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
            ) { result, purchases ->
                runCatching { completeCheck(result, purchases) }.onFailure { error ->
                    android.util.Log.e("PauzeXPro", "Unable to process subscription purchases", error)
                    runOnUiThread { statusLabel.text = "Couldn't check subscriptions. Try again shortly." }
                }
            }
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
            ) { result, purchases ->
                runCatching { completeCheck(result, purchases) }.onFailure { error ->
                    android.util.Log.e("PauzeXPro", "Unable to process lifetime purchases", error)
                    runOnUiThread { statusLabel.text = "Couldn't check lifetime purchase. Try again shortly." }
                }
            }
        } catch (error: Exception) {
            android.util.Log.e("PauzeXPro", "Unable to query owned purchases", error)
            runOnUiThread {
                statusLabel.text = "Google Play couldn't check purchases: " +
                    (error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            runOnUiThread { statusLabel.text = "Payment is pending. Pro will unlock after Google Play confirms it." }
            return
        }
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val ownedId = purchase.products.firstOrNull { id -> PLANS.any { it.id == id } } ?: return

        fun enablePro() {
            runOnUiThread {
                prefs.edit().putBoolean("pro_entitled", true).putString("pro_plan", ownedId).apply()
                statusLabel.text = "Purchase confirmed by Google Play. Pro is active."
                refreshEntitlementLabel()
                refreshPlanSelection()
                Toast.makeText(this, "PauzeX Pro activated", Toast.LENGTH_SHORT).show()
            }
        }

        if (purchase.isAcknowledged) {
            enablePro()
        } else {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(params) { result ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) enablePro()
                else runOnUiThread { statusLabel.text = "Payment found but acknowledgement failed. Reopen this screen to retry." }
            }
        }
    }

    private fun refreshEntitlementLabel() {
        entitlementLabel.text = if (prefs.getBoolean("pro_entitled", false)) {
            "✓ Pro is active on this device"
        } else {
            "Choose a plan to unlock Pro themes and future customisation packs."
        }
    }

    private fun openPlaySubscriptions() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions")))
        }.onFailure {
            statusLabel.text = "Open Google Play > Payments & subscriptions to manage your plan."
        }
    }

    override fun onResume() {
        super.onResume()
        if (::statusLabel.isInitialized && connected) queryOwnedPurchases(showMessage = false)
    }

    override fun onDestroy() {
        if (::billingClient.isInitialized) billingClient.endConnection()
        super.onDestroy()
    }
}
