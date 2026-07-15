package com.ee.tools.pricemonitor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private const val DATA_FILE_NAME = "price_products.json"
private const val TIME_FORMAT = "yyyy-MM-dd HH:mm:ss"
private const val TOO_GOOD_FACTOR = 0.65

private val ESTONIAN_SOURCES = listOf(
    Source("hinnavaatlus.ee", "https://www.hinnavaatlus.ee/search/?query=%s"),
    Source("hind.ee", "https://www.hind.ee/s/%s/"),
    Source("elux.ee", "https://www.elux.ee/et/search?text=%s"),
    Source("kaup24.ee", "https://kaup24.ee/et/search?keyword=%s"),
    Source("1a.ee", "https://www.1a.ee/search?query=%s"),
    Source("euronics.ee", "https://www.euronics.ee/otsing?q=%s"),
    Source("arvutitark.ee", "https://arvutitark.ee/est/otsing?search=%s"),
    Source("frog.ee", "https://frog.ee/search?controller=search&s=%s")
)

private val SKU_SOURCE_OVERRIDES = mapOf(
    "INCASSO.111.403" to setOf("elux.ee")
)

private val defaultPriceRegex = Regex("(?:USD|EUR|GBP|\\$)?\\s*([0-9]{1,3}(?:[.,][0-9]{3})*(?:[.,][0-9]{2})|[0-9]+(?:[.,][0-9]{2}))", RegexOption.IGNORE_CASE)
private val euroPriceRegex = Regex("([0-9]{1,3}(?:[ .][0-9]{3})*(?:,[0-9]{2})|[0-9]+(?:,[0-9]{2}))\\s*(?:EUR)", RegexOption.IGNORE_CASE)

class MainActivity : AppCompatActivity() {
    private lateinit var recycler: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var checkUseTooGood: CheckBox

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private var products: MutableList<Product> = mutableListOf()
    private lateinit var adapter: ProductAdapter
    private var selectedIndex: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recycler = findViewById(R.id.recyclerProducts)
        statusText = findViewById(R.id.txtStatus)
        checkUseTooGood = findViewById(R.id.checkUseTooGood)

        findViewById<android.widget.Button>(R.id.btnRefresh).setOnClickListener { refreshPrices() }
        findViewById<android.widget.Button>(R.id.btnAdd).setOnClickListener { showEditorDialog(addMode = true) }
        findViewById<android.widget.Button>(R.id.btnEdit).setOnClickListener { showEditorDialog(addMode = false) }
        findViewById<android.widget.Button>(R.id.btnRemove).setOnClickListener { removeSelected() }
        findViewById<android.widget.Button>(R.id.btnOpenLink).setOnClickListener { openSelectedLink() }

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = ProductAdapter(products) { index ->
            selectedIndex = index
            statusText.text = "Selected: ${products[index].name} / ${products[index].sku}"
        }
        recycler.adapter = adapter

        loadOrSeedData()
        refreshRows()
    }

    private fun dataFile(): File = File(filesDir, DATA_FILE_NAME)

    private fun loadOrSeedData() {
        val file = dataFile()
        if (!file.exists()) {
            assets.open("price_products_seed.json").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val json = file.readText(Charsets.UTF_8)
        val wrapperType = object : TypeToken<ProductWrapper>() {}.type
        val wrapper = gson.fromJson<ProductWrapper>(json, wrapperType)
        products.clear()
        products.addAll(wrapper.products)
    }

    private fun saveData() {
        val wrapper = ProductWrapper(products)
        dataFile().writeText(gson.toJson(wrapper), Charsets.UTF_8)
    }

    private fun nowText(): String = SimpleDateFormat(TIME_FORMAT, Locale.US).format(Date())

    private fun money(value: Double?): String = if (value == null) "-" else String.format(Locale.US, "EUR %,.2f", value)

    private fun normalizeCode(text: String): String = text.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    private fun queryCandidates(sku: String): List<String> {
        val raw = listOf(
            sku.trim(),
            sku.replace(".", "").trim(),
            sku.replace("-", "").trim(),
            sku.replace(" ", "").trim(),
            sku.replace(Regex("[^A-Za-z0-9]"), "").trim()
        )
        val seen = hashSetOf<String>()
        return raw.filter { it.isNotBlank() }.filter { seen.add(it.lowercase(Locale.US)) }
    }

    private fun hasSkuMatch(pageText: String, sku: String): Boolean {
        val lower = pageText.lowercase(Locale.US)
        val normalized = normalizeCode(pageText)
        for (candidate in queryCandidates(sku)) {
            if (lower.contains(candidate.lowercase(Locale.US))) return true
            val norm = normalizeCode(candidate)
            if (norm.isNotBlank() && normalized.contains(norm)) return true
        }
        return false
    }

    private fun parsePrice(raw: String): Double? {
        var cleaned = raw.trim().replace(" ", "")
        if (cleaned.isBlank()) return null

        if (cleaned.contains(',') && cleaned.contains('.')) {
            if (cleaned.lastIndexOf(',') > cleaned.lastIndexOf('.')) {
                cleaned = cleaned.replace(".", "").replace(',', '.')
            } else {
                cleaned = cleaned.replace(",", "")
            }
        } else {
            if (cleaned.count { it == ',' } == 1 && !cleaned.contains('.')) {
                val right = cleaned.substringAfter(',')
                cleaned = if (right.length == 2) cleaned.replace(',', '.') else cleaned.replace(",", "")
            } else if (cleaned.count { it == '.' } > 1) {
                cleaned = cleaned.replace(".", "")
            }
        }
        return cleaned.toDoubleOrNull()
    }

    private fun extractPrices(text: String, regex: Regex): List<Double> {
        val out = mutableListOf<Double>()
        regex.findAll(text).forEach { m ->
            val g = m.groupValues.getOrNull(1) ?: m.value
            val v = parsePrice(g)
            if (v != null && v in 10.0..50000.0) out.add(v)
        }
        return out
    }

    private fun getSourcesForSku(sku: String): List<Source> {
        val allowed = SKU_SOURCE_OVERRIDES[sku.uppercase(Locale.US)] ?: return ESTONIAN_SOURCES
        val scoped = ESTONIAN_SOURCES.filter { allowed.contains(it.name) }
        return if (scoped.isEmpty()) ESTONIAN_SOURCES else scoped
    }

    private fun fetchSitePrice(queryCode: String, source: Source, skuRef: String): OfferResult {
        val url = source.urlTemplate.format(Uri.encode(queryCode))
        return try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 13)")
                .timeout(12_000)
                .validateTLSCertificates(false)
                .get()

            val responseUrl = doc.location().lowercase(Locale.US)
            val text = doc.text()
            val lowText = text.lowercase(Locale.US)

            if (responseUrl.contains("zscalernotification") || lowText.contains("not allowed to browse")) {
                return OfferResult(source.name, url, null, "Blocked by network policy")
            }
            if (!hasSkuMatch(text, skuRef)) {
                return OfferResult(source.name, url, null, "SKU not present in results")
            }

            var prices = extractPrices(text, euroPriceRegex)
            if (prices.isEmpty()) prices = extractPrices(text, defaultPriceRegex)
            if (prices.isEmpty()) return OfferResult(source.name, url, null, "No prices found")

            OfferResult(source.name, url, prices.minOrNull(), "")
        } catch (e: Exception) {
            OfferResult(source.name, url, null, e.message ?: "Request error")
        }
    }

    private fun fetchAutoPrices(sku: String): List<OfferResult> {
        val out = mutableListOf<OfferResult>()
        val queries = queryCandidates(sku)
        for (source in getSourcesForSku(sku)) {
            var chosen: OfferResult? = null
            val errors = mutableListOf<String>()
            for (q in queries) {
                val attempt = fetchSitePrice(q, source, sku)
                if (attempt.price != null) {
                    chosen = attempt
                    break
                }
                if (attempt.error.isNotBlank()) errors.add(attempt.error)
            }
            if (chosen == null) {
                chosen = OfferResult(
                    source.name,
                    source.urlTemplate.format(Uri.encode(sku)),
                    null,
                    errors.distinct().joinToString(", ").ifBlank { "No results" }
                )
            }
            out.add(chosen)
        }
        return out
    }

    private fun chooseFallbackUrl(results: List<OfferResult>, sku: String): String {
        val preferred = listOf("elux.ee", "hind.ee", "hinnavaatlus.ee")
        preferred.forEach { n -> results.firstOrNull { it.sourceName == n && it.url.isNotBlank() }?.let { return it.url } }
        results.firstOrNull { it.url.isNotBlank() }?.let { return it.url }
        return "https://www.elux.ee/et/search?text=${Uri.encode(sku)}"
    }

    private fun isTooGood(candidate: OfferResult, valid: List<OfferResult>): Boolean {
        val c = candidate.price ?: return false
        val peers = valid.filter { it.sourceName != candidate.sourceName && it.price != null }.mapNotNull { it.price }
        if (peers.isEmpty()) return false
        val sorted = peers.sorted()
        val median = if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0 else sorted[sorted.size / 2]
        return c < median * TOO_GOOD_FACTOR
    }

    private fun chooseEffective(valid: List<OfferResult>, useTooGood: Boolean): Pair<OfferResult, OfferResult?> {
        val sorted = valid.sortedBy { it.price ?: Double.MAX_VALUE }
        val suspicious = sorted.filter { isTooGood(it, valid) }
        val suspiciousLowest = suspicious.firstOrNull()
        if (useTooGood || suspicious.isEmpty()) return Pair(sorted.first(), suspiciousLowest)
        val trusted = sorted.filterNot { suspicious.contains(it) }
        if (trusted.isNotEmpty()) return Pair(trusted.first(), suspiciousLowest)
        return Pair(sorted.first(), suspiciousLowest)
    }

    private fun refreshPrices() {
        if (products.isEmpty()) {
            statusText.text = "No products configured"
            return
        }

        val useTooGood = checkUseTooGood.isChecked
        statusText.text = "Refreshing prices..."

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val now = nowText()
                products.forEach { product ->
                    val sku = product.sku
                    val autoResults = fetchAutoPrices(sku)
                    val valid = autoResults.filter { it.price != null }
                    product.lastChecked = now

                    if (valid.isEmpty()) {
                        product.currentPrice = null
                        product.currentSource = ""
                        product.currentUrl = chooseFallbackUrl(autoResults, sku)
                        product.tooGoodPrice = false
                        val errors = autoResults.mapNotNull { it.error.takeIf { e -> e.isNotBlank() } }.distinct().joinToString(", ")
                        product.status = "No price found (${errors.ifBlank { "unknown reason" }})"
                        return@forEach
                    }

                    val (chosen, suspiciousLowest) = chooseEffective(valid, useTooGood)
                    val currentPrice = chosen.price
                    product.currentPrice = currentPrice
                    product.currentSource = chosen.sourceName
                    product.currentUrl = chosen.url

                    val tooGood = isTooGood(chosen, valid)
                    product.tooGoodPrice = tooGood

                    var suspiciousNote = ""
                    if (suspiciousLowest?.price != null) {
                        suspiciousNote = " | Too Good Seen ${money(suspiciousLowest.price)} @ ${suspiciousLowest.sourceName}"
                        if (!useTooGood) suspiciousNote += " (excluded)"
                    }

                    var lowestPrice = product.lowestPrice
                    var correctedLowest = false
                    if (
                        suspiciousLowest?.price != null &&
                        lowestPrice != null &&
                        currentPrice != null &&
                        !tooGood &&
                        product.lowestSource == suspiciousLowest.sourceName &&
                        abs(lowestPrice - suspiciousLowest.price) < 0.01
                    ) {
                        product.lowestPrice = currentPrice
                        product.lowestSource = chosen.sourceName
                        product.lowestSeenAt = now
                        correctedLowest = true
                        lowestPrice = currentPrice
                    }

                    if (!tooGood && (lowestPrice == null || (currentPrice != null && currentPrice < lowestPrice))) {
                        product.lowestPrice = currentPrice
                        product.lowestSource = chosen.sourceName
                        product.lowestSeenAt = now
                        val correction = if (correctedLowest) " | Lowest corrected (removed too-good)" else ""
                        product.status = "New Lowest Price$correction$suspiciousNote"
                    } else {
                        if (tooGood) {
                            product.status = "Checked | Too Good Price? Verify$suspiciousNote"
                        } else {
                            val correction = if (correctedLowest) " | Lowest corrected (removed too-good)" else ""
                            product.status = "Checked$correction$suspiciousNote"
                        }
                    }
                }
                saveData()
            }

            refreshRows()
            statusText.text = "Updated ${products.size} products at ${nowText()}"
        }
    }

    private fun refreshRows() {
        adapter.submit(products.toList())
        if (selectedIndex >= products.size) selectedIndex = -1
    }

    private fun rowText(product: Product): String {
        val diff = if (product.currentPrice != null && product.lowestPrice != null) {
            val d = product.currentPrice!! - product.lowestPrice!!
            String.format(Locale.US, "EUR %+,.2f", d)
        } else "-"

        return buildString {
            append("${product.name} / ${product.sku}\n")
            append("Current: ${money(product.currentPrice)} | Lowest: ${money(product.lowestPrice)} | Diff: $diff\n")
            append("Source: ${product.currentSource.ifBlank { "-" }}\n")
            append("Status: ${product.status}")
        }
    }

    private fun rowColor(product: Product): Int {
        return when {
            product.status.contains("Too Good Price", ignoreCase = true) -> Color.parseColor("#FFF7D6")
            product.status.contains("New Lowest Price", ignoreCase = true) -> Color.parseColor("#D8F6DC")
            product.currentPrice == null -> Color.parseColor("#F8D7DA")
            else -> Color.parseColor("#F0F0F0")
        }
    }

    private fun showEditorDialog(addMode: Boolean) {
        if (!addMode && (selectedIndex < 0 || selectedIndex >= products.size)) {
            toast("Select a product first")
            return
        }

        val existing = if (addMode) null else products[selectedIndex]
        val nameInput = EditText(this).apply {
            hint = "Product name"
            setText(existing?.name ?: "")
        }
        val skuInput = EditText(this).apply {
            hint = "SKU / part number"
            setText(existing?.sku ?: "")
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 24, 40, 10)
            addView(nameInput)
            addView(skuInput)
        }

        AlertDialog.Builder(this)
            .setTitle(if (addMode) "Add Product" else "Edit Product")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val sku = skuInput.text.toString().trim().uppercase(Locale.US)
                if (name.isBlank() || sku.isBlank()) {
                    toast("Name and SKU are required")
                    return@setPositiveButton
                }
                val dup = products.anyIndexed { idx, p -> p.sku.equals(sku, true) && (addMode || idx != selectedIndex) }
                if (dup) {
                    toast("SKU already exists")
                    return@setPositiveButton
                }

                if (addMode) {
                    products.add(Product(name = name, sku = sku))
                    selectedIndex = products.lastIndex
                } else {
                    val p = products[selectedIndex]
                    p.name = name
                    p.sku = sku
                }
                saveData()
                refreshRows()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeSelected() {
        if (selectedIndex < 0 || selectedIndex >= products.size) {
            toast("Select a product first")
            return
        }
        val target = products[selectedIndex]
        AlertDialog.Builder(this)
            .setTitle("Remove Product")
            .setMessage("Remove ${target.sku}?")
            .setPositiveButton("Remove") { _, _ ->
                products.removeAt(selectedIndex)
                selectedIndex = -1
                saveData()
                refreshRows()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSelectedLink() {
        if (selectedIndex < 0 || selectedIndex >= products.size) {
            toast("Select a product first")
            return
        }
        val url = products[selectedIndex].currentUrl.trim()
        if (url.isBlank()) {
            toast("No link available")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            toast("No browser found")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private inner class ProductAdapter(
        private var data: List<Product>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<ProductAdapter.Holder>() {

        inner class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val view = layoutInflater.inflate(R.layout.item_product, parent, false) as TextView
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val p = data[position]
            holder.text.text = rowText(p)
            holder.text.setBackgroundColor(rowColor(p))
            holder.text.setOnClickListener {
                onClick(position)
            }
        }

        override fun getItemCount(): Int = data.size

        fun submit(newData: List<Product>) {
            data = newData
            notifyDataSetChanged()
        }
    }
}

data class Source(val name: String, val urlTemplate: String)

data class OfferResult(
    val sourceName: String,
    val url: String,
    val price: Double?,
    val error: String
)

data class ProductWrapper(
    @SerializedName("products") val products: MutableList<Product>
)

data class Product(
    @SerializedName("name") var name: String,
    @SerializedName("sku") var sku: String,
    @SerializedName("offers") var offers: MutableList<Map<String, String>> = mutableListOf(),
    @SerializedName("lowest_price") var lowestPrice: Double? = null,
    @SerializedName("lowest_source") var lowestSource: String = "",
    @SerializedName("lowest_seen_at") var lowestSeenAt: String = "",
    @SerializedName("current_price") var currentPrice: Double? = null,
    @SerializedName("current_source") var currentSource: String = "",
    @SerializedName("last_checked") var lastChecked: String = "",
    @SerializedName("status") var status: String = "Not checked",
    @SerializedName("current_url") var currentUrl: String = "",
    @SerializedName("too_good_price") var tooGoodPrice: Boolean = false
)

private inline fun <T> Iterable<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
    var i = 0
    for (item in this) {
        if (predicate(i, item)) return true
        i++
    }
    return false
}
