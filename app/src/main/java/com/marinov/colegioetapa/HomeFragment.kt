package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.Calendar
import java.util.Objects

class HomeFragment : Fragment(), MainActivity.LoginStateListener {

    private var layoutSemInternet: LinearLayout? = null
    private var btnTentarNovamente: MaterialButton? = null
    private var loadingContainer: View? = null
    private var contentContainer: View? = null
    private var txtStuckHint: TextView? = null
    private var topLoadingBar: View? = null
    private var recentGradesContainer: LinearLayout? = null

    private var isFragmentDestroyed = false
    private var isDataLoaded = false

    private var recentGradesCache: List<NotaRecente> = emptyList()
    private var calendarExamsCache: List<ProvaCalendario> = emptyList()

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var backPressedCallback: OnBackPressedCallback

    data class ProvaCalendario(val data: Calendar, val codigo: String, val conjunto: Int)
    data class Nota(val codigo: String, val conjunto: Int, val valor: String)
    data class NotaRecente(val codigo: String, val conjunto: String, val nota: String, val data: Calendar) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as NotaRecente
            return codigo == other.codigo && conjunto == other.conjunto && nota == other.nota
        }

        override fun hashCode(): Int {
            return Objects.hash(codigo, conjunto, nota)
        }
    }

    companion object {
        const val PREFS_NAME = "HomeFragmentCache"
        const val KEY_RECENT_GRADES = "recent_grades"
        const val KEY_CACHE_TIMESTAMP = "cache_timestamp"
        const val KEY_CALENDAR_EXAMS = "calendar_exams"
        const val KEY_CALENDAR_TIMESTAMP = "calendar_timestamp"
        const val SEVEN_DAYS_IN_MILLIS = 7 * 24 * 60 * 60 * 1000L // 7 dias em milissegundos

        const val HOME_URL = "https://areaexclusiva.colegioetapa.com.br/home"
        const val NOTAS_URL = "https://areaexclusiva.colegioetapa.com.br/provas/notas"
        const val CALENDARIO_URL_BASE = "https://areaexclusiva.colegioetapa.com.br/provas/datas"
        const val LOGIN_URL = "https://areaexclusiva.colegioetapa.com.br"
        const val MAX_RECENT_GRADES = 3
        const val MESES = 12
        const val TAG = "HomeFragment"
        const val AUTOFILL_PREFS = "autofill_prefs"

        @JvmStatic
        fun fetchPageDataStatic(url: String): Document? {
            return try {
                val cookieManager = CookieManager.getInstance()
                val cookies = cookieManager.getCookie(url)
                Jsoup.connect(url)
                    .header("Cookie", cookies ?: "")
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/536.36")
                    .timeout(20000)
                    .get()
            } catch (e: IOException) {
                Log.w(TAG, "fetchPageDataStatic erro: ${e.message}")
                null
            }
        }

        @JvmStatic
        fun isValidSessionStatic(doc: Document?): Boolean {
            if (doc == null) return false
            val homeCarousel = doc.getElementById("home_banners_carousel")
            return homeCarousel != null
        }

        @JvmStatic
        fun isSystemDarkModeStatic(ctx: Context): Boolean {
            return (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "Back pressed no HomeFragment - verificando se deve fechar app")
                val mainActivity = activity as? MainActivity
                val viewPager = mainActivity?.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.nav_host_fragment)
                val currentItem = viewPager?.currentItem ?: -1
                val backStackCount = parentFragmentManager.backStackEntryCount
                Log.d(TAG, "Current item: $currentItem, backstack count: $backStackCount")
                if (currentItem == 0 && backStackCount == 0) {
                    Log.d(TAG, "Condições atendidas - fechando app")
                    requireActivity().finish()
                } else {
                    Log.d(TAG, "Condições não atendidas - não fechando app")
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isFragmentDestroyed) isEnabled = true
                    }, 100)
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home_watch, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isFragmentDestroyed = false
        initializeViews(view)
        setupListeners()
        checkInternetAndLoadData()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "HomeFragment onResume - verificando se deve ativar callback")
        handler.post {
            if (isFragmentDestroyed) return@post
            val mainActivity = activity as? MainActivity
            if (mainActivity != null) {
                val viewPager = mainActivity.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.nav_host_fragment)
                val currentItem = viewPager?.currentItem ?: -1
                val backStackCount = parentFragmentManager.backStackEntryCount
                Log.d(TAG, "onResume check - ViewPager item: $currentItem, backstack: $backStackCount")
                if (currentItem == 0 && backStackCount == 0) {
                    backPressedCallback.isEnabled = true
                    Log.d(TAG, "Callback de back ATIVADO")
                } else {
                    backPressedCallback.isEnabled = false
                    Log.d(TAG, "Callback de back DESATIVADO")
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "HomeFragment onPause - desativando callback de back")
        backPressedCallback.isEnabled = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isFragmentDestroyed = true
        handler.removeCallbacksAndMessages(null)
    }

    private fun initializeViews(view: View) {
        loadingContainer = view.findViewById(R.id.loadingContainer)
        contentContainer = view.findViewById(R.id.contentContainer)
        layoutSemInternet = view.findViewById(R.id.telaOffline)
        btnTentarNovamente = view.findViewById(R.id.btn_tentar_novamente)
        txtStuckHint = view.findViewById(R.id.txtStuckHint)
        topLoadingBar = view.findViewById(R.id.top_loading_bar)
        recentGradesContainer = view.findViewById(R.id.recentGradesContainer)
    }

    private fun setupListeners() {
        btnTentarNovamente?.setOnClickListener {
            checkInternetAndLoadData()
        }
    }

    private fun checkInternetAndLoadData() {
        if (loadCache()) {
            Log.d(TAG, "Cache encontrado. Exibindo dados de cache.")
            updateUiWithCurrentData()
            showContentState()
            isDataLoaded = true
        }
        if (hasInternetConnection()) {
            if (!isDataLoaded) {
                showLoadingState()
            }
            fetchDataInBackground()
        } else {
            if (!isDataLoaded) {
                showOfflineState()
            }
        }
    }

    private fun fetchDataInBackground() {
        if (isDataLoaded) {
            topLoadingBar?.visibility = View.VISIBLE
        }
        lifecycleScope.launch {
            try {
                val homeDoc = withContext(Dispatchers.IO) { fetchPageDataStatic(HOME_URL) }
                if (isFragmentDestroyed) return@launch
                if (isValidSessionStatic(homeDoc)) {
                    // --- LÓGICA DE CACHE DO CALENDÁRIO (7 DIAS) ---
                    val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val calendarTimestamp = prefs.getLong(KEY_CALENDAR_TIMESTAMP, 0)
                    val isCalendarCacheValid = System.currentTimeMillis() - calendarTimestamp < SEVEN_DAYS_IN_MILLIS

                    val allExams: List<ProvaCalendario>
                    if (isCalendarCacheValid && calendarExamsCache.isNotEmpty()) {
                        Log.d(TAG, "Usando calendário do cache.")
                        allExams = calendarExamsCache
                    } else {
                        Log.d(TAG, "Cache do calendário inválido ou vazio. Buscando da web.")
                        val calendarDocsDeferred = (1..MESES).map { mes ->
                            async(Dispatchers.IO) { fetchPageDataStatic("$CALENDARIO_URL_BASE?mes%5B%5D=$mes") }
                        }
                        val calendarDocs = try { awaitAll(*calendarDocsDeferred.toTypedArray()) } catch (_: Exception) { emptyList() }
                        allExams = parseAllCalendarData(calendarDocs)
                        calendarExamsCache = allExams
                        saveCalendarCache(allExams) // Salva o novo calendário e o timestamp
                    }
                    // --- FIM DA LÓGICA DE CACHE DO CALENDÁRIO ---

                    // Busca as notas (sempre busca, conforme solicitado)
                    val gradesDoc = async(Dispatchers.IO) { fetchPageDataStatic(NOTAS_URL) }.await()
                    val allGrades = parseAllGradesData(gradesDoc)
                    val recentGrades = findRecentGrades(allExams, allGrades)

                    if (recentGrades != recentGradesCache) {
                        Log.d(TAG, "Novas notas encontradas. Atualizando a UI.")
                        recentGradesCache = recentGrades
                        saveRecentGradesCache(recentGrades)
                    }

                    withContext(Dispatchers.Main) {
                        if (isFragmentDestroyed) return@withContext
                        updateUiWithCurrentData()
                        isDataLoaded = true
                        showContentState()
                    }
                } else {
                    withContext(Dispatchers.Main) { handleInvalidSession() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao buscar dados em background", e)
                withContext(Dispatchers.Main) { if (!isFragmentDestroyed && !isDataLoaded) showOfflineState() }
            } finally {
                withContext(Dispatchers.Main) { if (!isFragmentDestroyed) topLoadingBar?.visibility = View.GONE }
            }
        }
    }

    private fun parseAllCalendarData(docs: List<Document?>): List<ProvaCalendario> {
        val allExams = mutableListOf<ProvaCalendario>()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        docs.filterNotNull().forEach { doc ->
            val table = doc.selectFirst("table") ?: return@forEach
            val rows = table.select("tbody > tr")
            for (tr in rows) {
                val cells = tr.children()
                if (cells.size < 5) continue
                try {
                    // VERIFICAÇÃO PARA IGNORAR RECUPERAÇÕES
                    if (cells[2].text().lowercase().contains("rec")) continue

                    val dataStr = cells[0].text().split(" ")[0]
                    val codigo = cells[1].ownText()
                    val conjuntoStr = cells[3].text().filter { it.isDigit() }

                    if (dataStr.contains('/') && conjuntoStr.isNotEmpty()) {
                        val dataParts = dataStr.split("/")
                        val day = dataParts[0].toInt()
                        val month = dataParts[1].toInt() - 1
                        val conjunto = conjuntoStr.toInt()

                        val calendar = Calendar.getInstance().apply {
                            set(currentYear, month, day, 0, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        allExams.add(ProvaCalendario(calendar, codigo, conjunto))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao parsear linha do calendário: ${tr.text()}", e)
                }
            }
        }
        return allExams
    }

    private fun parseAllGradesData(doc: Document?): List<Nota> {
        if (doc == null) return emptyList()
        val allGrades = mutableListOf<Nota>()
        val table = doc.selectFirst("table") ?: return emptyList()

        val headers = table.select("thead th")
        val conjuntoMap = mutableMapOf<Int, Int>()
        headers.forEachIndexed { index, th ->
            if (index > 1) {
                val conjunto = th.text().filter { it.isDigit() }.toIntOrNull()
                if (conjunto != null) conjuntoMap[index] = conjunto
            }
        }

        val rows = table.select("tbody > tr")
        for (tr in rows) {
            val cols = tr.children()
            if (cols.size <= 1) continue
            val codigo = cols[1].text()

            cols.forEachIndexed { colIndex, td ->
                conjuntoMap[colIndex]?.let { conjunto ->
                    // *** LÓGICA ATUALIZADA PARA EXTRAIR A NOTA ***
                    val notaContainer = td.select("div.d-flex.flex-column").firstOrNull {
                        it.selectFirst("span.font-weight-bold")?.text()?.equals("Nota", ignoreCase = true) == true
                    }
                    val nota = if (notaContainer != null) {
                        notaContainer.text().replace("Nota", "", ignoreCase = true).trim()
                    } else {
                        td.text().trim()
                    }
                    // *** FIM DA LÓGICA ATUALIZADA ***

                    if (nota.isNotEmpty()) {
                        allGrades.add(Nota(codigo, conjunto, nota))
                    }
                }
            }
        }
        return allGrades
    }

    private fun findRecentGrades(allExams: List<ProvaCalendario>, allGrades: List<Nota>): List<NotaRecente> {
        if (allExams.isEmpty() || allGrades.isEmpty()) return emptyList()

        val gradesMap = allGrades.associateBy { "${it.codigo}-${it.conjunto}" }
        val today = Calendar.getInstance()

        val recentGrades = allExams
            .filter { it.data.before(today) || it.data == today }
            .sortedByDescending { it.data }
            .mapNotNull { exam ->
                val key = "${exam.codigo}-${exam.conjunto}"
                gradesMap[key]?.let { nota ->
                    if (nota.valor != "--") {
                        NotaRecente(
                            exam.codigo,
                            exam.conjunto.toString(),
                            nota.valor,
                            exam.data
                        )
                    } else null
                }
            }
            .distinctBy { "${it.codigo}-${it.conjunto}" }
            .take(MAX_RECENT_GRADES)

        return recentGrades.sortedByDescending { it.data }
    }

    private fun updateUiWithCurrentData() {
        if (isFragmentDestroyed) return
        setupRecentGrades(recentGradesCache)
    }

    private fun setupRecentGrades(grades: List<NotaRecente>) {
        if (isFragmentDestroyed || recentGradesContainer == null) return
        val context = context ?: return
        recentGradesContainer?.removeAllViews()
        if (grades.isEmpty()) {
            recentGradesContainer?.visibility = View.GONE
            return
        }
        recentGradesContainer?.visibility = View.VISIBLE
        for (grade in grades) {
            val gradeView = LayoutInflater.from(context).inflate(R.layout.item_recent_grade_watch, recentGradesContainer, false)
            gradeView.findViewById<TextView>(R.id.tv_codigo).text = grade.codigo
            gradeView.findViewById<TextView>(R.id.tv_conjunto).text = "Conjunto ${grade.conjunto}"
            gradeView.findViewById<TextView>(R.id.tv_nota).text = grade.nota
            recentGradesContainer?.addView(gradeView)
        }
    }

    override fun onLoginSuccess() {
        Log.d(TAG, "Login bem-sucedido - forçando recarregamento do HomeFragment")
        isDataLoaded = false
        clearCache()
        checkInternetAndLoadData()
    }

    private fun handleInvalidSession() {
        if (isFragmentDestroyed) return
        isDataLoaded = false
        clearCache()
        showLoginPopup()
    }

    private fun saveRecentGradesCache(grades: List<NotaRecente>) {
        if (isFragmentDestroyed) return
        val context = context ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_RECENT_GRADES, Gson().toJson(grades))
            putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
        }
    }

    private fun saveCalendarCache(exams: List<ProvaCalendario>) {
        if (isFragmentDestroyed) return
        val context = context ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_CALENDAR_EXAMS, Gson().toJson(exams))
            putLong(KEY_CALENDAR_TIMESTAMP, System.currentTimeMillis())
        }
    }

    private fun loadCache(): Boolean {
        if (isFragmentDestroyed) return false
        val context = context ?: return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Carrega notas recentes
        val recentGradesJson = prefs.getString(KEY_RECENT_GRADES, null)
        if (recentGradesJson != null) {
            recentGradesCache = Gson().fromJson(recentGradesJson, object : TypeToken<List<NotaRecente>>() {}.type)
        }

        // Carrega calendário
        val calendarExamsJson = prefs.getString(KEY_CALENDAR_EXAMS, null)
        if (calendarExamsJson != null) {
            calendarExamsCache = Gson().fromJson(calendarExamsJson, object : TypeToken<List<ProvaCalendario>>() {}.type)
        }

        // Verifica validade do cache de notas (24 horas)
        val cacheTimestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0)
        if (System.currentTimeMillis() - cacheTimestamp > 24 * 60 * 60 * 1000L) {
            clearRecentGradesCache()
            return false
        }

        return recentGradesJson != null
    }

    private fun clearCache() {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit {
            clear()
            apply()
        }
        recentGradesCache = emptyList()
        calendarExamsCache = emptyList()
    }

    private fun clearRecentGradesCache() {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit {
            remove(KEY_RECENT_GRADES)
            remove(KEY_CACHE_TIMESTAMP)
            apply()
        }
        recentGradesCache = emptyList()
    }

    private fun showLoginPopup() {
        val fragmentManager = activity?.supportFragmentManager ?: return
        if (fragmentManager.findFragmentByTag("login_dialog") == null) {
            LoginDialogFragment().apply { isCancelable = false }.show(fragmentManager, "login_dialog")
        }
    }

    class LoginDialogFragment : DialogFragment() {
        private lateinit var webView: WebView
        private lateinit var progress: ProgressBar

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            return super.onCreateDialog(savedInstanceState).apply {
                setCanceledOnTouchOutside(false)
                isCancelable = false
                setOnKeyListener { _, keyCode, event -> keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP }
            }
        }

        override fun onStart() {
            super.onStart()
            dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            dialog?.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            return FrameLayout(requireContext()).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                webView = WebView(requireContext()).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    visibility = View.INVISIBLE
                }
                progress = ProgressBar(requireContext()).apply {
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER)
                }
                addView(webView)
                addView(progress)
                initializeLoginWebView()
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun initializeLoginWebView() {
            CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(webView, true); flush() }
            with(webView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Safari/605.1.15"

                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                    @Suppress("DEPRECATION")
                    WebSettingsCompat.setForceDark(
                        this,
                        if (isSystemDarkModeStatic(requireContext())) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                    )
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY) && isSystemDarkModeStatic(requireContext())) {
                    @Suppress("DEPRECATION")
                    WebSettingsCompat.setForceDarkStrategy(
                        this,
                        WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY
                    )
                }
            }
            webView.addJavascriptInterface(JsInterface(requireContext().getSharedPreferences(AUTOFILL_PREFS, Context.MODE_PRIVATE)), "AndroidAutofill")
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (request?.url?.toString()?.startsWith(HOME_URL) == true) {
                        onLoginDetected()
                        return true
                    }
                    return false
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (!isAdded) return
                    injectJs(view, getRemovalScript())
                    injectJs(view, "(function(){const u=document.querySelector('#matricula'),p=document.querySelector('#senha');if(u&&p){u.value=AndroidAutofill.getSavedUser();p.value=AndroidAutofill.getSavedPassword();const h=()=>{AndroidAutofill.saveCredentials(u.value,p.value)};u.addEventListener('input',h);p.addEventListener('input',h)}})();")
                    if (isSystemDarkModeStatic(requireContext())) {
                        injectJs(view, getDarkModeScript())
                    }
                    if (url.startsWith(HOME_URL)) {
                        onLoginDetected()
                    } else {
                        progress.visibility = View.GONE
                        view.visibility = View.VISIBLE
                    }
                }
            }
            webView.loadUrl(LOGIN_URL)
        }

        private fun onLoginDetected() {
            if (!isAdded || isRemoving) return
            CookieManager.getInstance().flush()

            lifecycleScope.launch {
                delay(150)
                if (isAdded) {
                    (activity as? MainActivity)?.onGlobalLoginSuccess()
                    dismissAllowingStateLoss()
                }
            }
        }

        private fun injectJs(view: WebView, script: String) {
            view.evaluateJavascript(script, null)
        }

        private fun getRemovalScript(): String {
            return """
                document.documentElement.style.webkitTouchCallout='none';
                document.documentElement.style.webkitUserSelect='none';
                var nav=document.querySelector('#page-content-wrapper > nav'); 
                if(nav) nav.remove(); 
                var sidebar=document.querySelector('#sidebar-wrapper'); 
                if(sidebar) sidebar.remove(); 
                var responsavelTab=document.querySelector('#responsavel-tab'); 
                if(responsavelTab) responsavelTab.remove(); 
                var alunoTab=document.querySelector('#aluno-tab'); 
                if(alunoTab) alunoTab.remove(); 
                var login=document.querySelector('#login'); 
                if(login) login.remove(); 
                var cardElement=document.querySelector('body > div.row.mx-0.pt-4 > div > div.card.mt-4.border-radius-card.border-0.shadow'); 
                if(cardElement) cardElement.remove(); 
                var backButton = document.querySelector('#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div:nth-child(1) > div.card-header.bg-soft-blue.border-left-blue.text-blue.rounded > i.fas.fa-chevron-left.btn-outline-primary.py-1.px-2.rounded.mr-2');
                if (backButton) backButton.remove(); 
                var darkHeader = document.querySelector('#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-header.bg-dark.rounded.d-flex.align-items-center.justify-content-center');
                if (darkHeader) darkHeader.remove(); 
                var style=document.createElement('style');
                style.type='text/css';
                style.appendChild(document.createTextNode('::-webkit-scrollbar{display:none;}'));
                document.head.appendChild(style);
            """.trimIndent()
        }

        private fun getDarkModeScript(): String {
            return """
                var darkModeStyle = document.createElement('style');
                darkModeStyle.type = 'text/css';
                darkModeStyle.innerHTML = `
                    html {
                        filter: invert(1) hue-rotate(180deg) !important;
                        background: #121212 !important;
                    }
                    img, picture, video, iframe {
                        filter: invert(1) hue-rotate(180deg) !important;
                    }
                `;
                document.head.appendChild(darkModeStyle);
            """.trimIndent()
        }

        private inner class JsInterface(private val prefs: android.content.SharedPreferences) {
            @JavascriptInterface fun saveCredentials(u: String, p: String) = prefs.edit { putString("user", u); putString("password", p) }
            @JavascriptInterface fun getSavedUser(): String = prefs.getString("user", "") ?: ""
            @JavascriptInterface fun getSavedPassword(): String = prefs.getString("password", "") ?: ""
        }
    }

    private fun showLoadingState() {
        if (isFragmentDestroyed) return
        loadingContainer?.visibility = View.VISIBLE
        contentContainer?.visibility = View.GONE
        layoutSemInternet?.visibility = View.GONE
    }

    private fun showContentState() {
        if (isFragmentDestroyed) return
        loadingContainer?.visibility = View.GONE
        contentContainer?.visibility = View.VISIBLE
        layoutSemInternet?.visibility = View.GONE
    }

    private fun showOfflineState() {
        if (isFragmentDestroyed) return
        loadingContainer?.visibility = View.GONE
        contentContainer?.visibility = View.GONE
        layoutSemInternet?.visibility = View.VISIBLE
    }

    private fun hasInternetConnection(): Boolean {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}