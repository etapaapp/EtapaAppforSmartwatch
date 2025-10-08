package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class CalendarioProvas : Fragment() {

    private companion object {
        const val URL_BASE = "https://areaexclusiva.colegioetapa.com.br/provas/datas"
        const val PREFS = "calendario_prefs"
        const val KEY_BASE = "cache_html_calendario_"
        const val KEY_SEM_PROVAS = "sem_provas_"
        const val KEY_FILTRO = "filtro_provas"
        const val FILTRO_TODOS = 0
        const val FILTRO_PROVAS = 1
        const val FILTRO_RECUPERACOES = 2
    }

    private lateinit var recyclerProvas: RecyclerView
    private lateinit var progressBar: CircularProgressIndicator
    private lateinit var barOffline: View
    private lateinit var txtSemProvas: TextView
    private lateinit var txtSemDados: TextView
    private lateinit var btnLogin: MaterialButton
    private lateinit var spinnerMes: Spinner
    private lateinit var btnFiltro: ImageButton
    private lateinit var adapter: ProvasAdapter
    private lateinit var cache: CacheHelper
    private var mesSelecionado: Int = 0
    private var filtroAtual: Int = FILTRO_TODOS
    private lateinit var prefs: SharedPreferences
    private var fetchJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_provas_calendar, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!isAdded) return

        recyclerProvas = view.findViewById(R.id.recyclerProvas)
        progressBar = view.findViewById(R.id.progress_circular)
        barOffline = view.findViewById(R.id.barOffline)
        txtSemProvas = view.findViewById(R.id.txt_sem_provas)
        txtSemDados = view.findViewById(R.id.txt_sem_dados)
        spinnerMes = view.findViewById(R.id.spinner_mes)
        btnLogin = view.findViewById(R.id.btnLogin)
        btnFiltro = view.findViewById(R.id.btnFiltro)

        setupRecyclerView()
        configurarSpinnerMeses()
        cache = CacheHelper(requireContext())

        prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        filtroAtual = prefs.getInt(KEY_FILTRO, FILTRO_TODOS)

        carregarDadosParaMes()

        btnLogin.setOnClickListener {
            (activity as? MainActivity)?.navigateToHome()
        }

        btnFiltro.setOnClickListener {
            mostrarMenuFiltro(it)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fetchJob?.cancel()
    }

    private fun setupRecyclerView() {
        if (!isAdded) return
        recyclerProvas.layoutManager = LinearLayoutManager(requireContext())
        adapter = ProvasAdapter(emptyList(), this)
        recyclerProvas.adapter = adapter
    }

    private fun configurarSpinnerMeses() {
        if (!isAdded) return
        val adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.meses_array,
            android.R.layout.simple_spinner_item
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        spinnerMes.adapter = adapter
        spinnerMes.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                mesSelecionado = position
                carregarDadosParaMes()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun mostrarMenuFiltro(anchor: View) {
        if (!isAdded) return
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_filtro_provas, popup.menu)

        when (filtroAtual) {
            FILTRO_TODOS -> popup.menu.findItem(R.id.filtro_todos).isChecked = true
            FILTRO_PROVAS -> popup.menu.findItem(R.id.filtro_provas).isChecked = true
            FILTRO_RECUPERACOES -> popup.menu.findItem(R.id.filtro_recuperacoes).isChecked = true
        }

        popup.setOnMenuItemClickListener { item ->
            filtroAtual = when (item.itemId) {
                R.id.filtro_todos -> FILTRO_TODOS
                R.id.filtro_provas -> FILTRO_PROVAS
                R.id.filtro_recuperacoes -> FILTRO_RECUPERACOES
                else -> return@setOnMenuItemClickListener false
            }

            prefs.edit { putInt(KEY_FILTRO, filtroAtual) }
            adapter.aplicarFiltro(filtroAtual)
            true
        }

        popup.show()
    }

    private fun carregarDadosParaMes() {
        if (!isAdded) return
        if (!isOnline()) {
            barOffline.visibility = View.VISIBLE
            progressBar.visibility = View.GONE
            verificarCache()
        } else {
            exibirCarregando()
            val url = if (mesSelecionado == 0) URL_BASE else "$URL_BASE?mes%5B%5D=$mesSelecionado"
            fetchProvas(url)
        }
    }

    private fun verificarCache() {
        if (!isAdded) return
        when {
            cache.temProvas(mesSelecionado) -> carregarCacheProvas()
            cache.mesSemProvas(mesSelecionado) -> exibirMensagemSemProvas()
            else -> exibirSemDados()
        }
    }

    private fun exibirMensagemSemProvas() {
        if (!isAdded) return
        recyclerProvas.visibility = View.GONE
        txtSemProvas.visibility = View.VISIBLE
        txtSemDados.visibility = View.GONE
    }

    private fun carregarCacheProvas() {
        if (!isAdded) return
        cache.loadHtml(mesSelecionado)?.let { html ->
            val fake = Jsoup.parse(html)
            val table = fake.selectFirst("table")
            if (table != null) {
                parseAndDisplayTable(table)
                exibirConteudoOnline()
            }
        } ?: run {
            // Se não há cache de provas, verifica se o mês está marcado como sem provas
            if (cache.mesSemProvas(mesSelecionado)) {
                exibirMensagemSemProvas()
            } else {
                exibirSemDados()
            }
        }
    }

    private fun fetchProvas(url: String) {
        fetchJob?.cancel()

        fetchJob = lifecycleScope.launch {
            try {
                val doc = withContext(Dispatchers.IO) {
                    ensureActive()
                    try {
                        val cookieHeader = CookieManager.getInstance().getCookie(url)
                        Jsoup.connect(url)
                            .header("Cookie", cookieHeader)
                            .userAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 18_6_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/140.0.7339.39 Mobile/15E148 Safari/604.1")
                            .timeout(15000)
                            .get()
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            Log.e("CalendarioProvas", "Erro na conexão", e)
                        }
                        null
                    }
                }

                ensureActive()
                if (!isAdded) return@launch

                progressBar.visibility = View.GONE

                // Verifica se há a mensagem de nenhuma prova primeiro
                val alertInfo = doc?.selectFirst("div.alert.alert-info")
                if (alertInfo != null && alertInfo.text().contains("Nenhuma prova a ser mostrada", ignoreCase = true)) {
                    barOffline.visibility = View.GONE
                    cache.salvarMesSemProvas(mesSelecionado)
                    exibirMensagemSemProvas()
                    return@launch
                }

                // Se não há mensagem de nenhuma prova, verifica se há tabela
                val table = doc?.selectFirst("table")
                if (table != null) {
                    barOffline.visibility = View.GONE
                    cache.salvarProvas(table.outerHtml(), mesSelecionado)
                    parseAndDisplayTable(table)
                    exibirConteudoOnline()
                } else {
                    // Se não há tabela nem mensagem de nenhuma prova, exibe barra offline e verifica cache
                    if (isOnline()) {
                        barOffline.visibility = View.VISIBLE
                    }
                    verificarCache()
                }
            } catch (_: CancellationException) {
                Log.d("CalendarioProvas", "Requisição cancelada")
            } catch (e: Exception) {
                if (!isAdded) return@launch
                Log.e("CalendarioProvas", "Exceção no fetchProvas", e)
                progressBar.visibility = View.GONE
                if (isOnline()) {
                    barOffline.visibility = View.VISIBLE
                }
                verificarCache()
            }
        }
    }

    private fun exibirCarregando() {
        if (!isAdded) return
        barOffline.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        recyclerProvas.visibility = View.GONE
        txtSemProvas.visibility = View.GONE
        txtSemDados.visibility = View.GONE
    }

    private fun exibirConteudoOnline() {
        if (!isAdded) return
        recyclerProvas.visibility = View.VISIBLE
        txtSemProvas.visibility = View.GONE
        txtSemDados.visibility = View.GONE
    }

    private fun exibirSemDados() {
        if (!isAdded) return
        recyclerProvas.visibility = View.GONE
        txtSemProvas.visibility = View.GONE
        txtSemDados.visibility = View.VISIBLE
    }

    private fun isOnline(): Boolean {
        if (!isAdded) return false
        return try {
            val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network: Network? = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
            return networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            false
        }
    }

    private fun parseAndDisplayTable(table: Element) {
        if (!isAdded) return

        val items = mutableListOf<ProvaItem>()
        val rows = table.select("tbody > tr")

        for (tr in rows) {
            val cells = tr.children()
            if (cells.size < 5) continue

            val data = cells[0].text()
            val codigo = cells[1].ownText()
            val linkElement = cells[1].selectFirst("a")
            val link = linkElement?.attr("href") ?: ""
            val tipo = cells[2].text()
            val conjunto = "${cells[3].text()}° conjunto"
            val materia = cells[4].text()

            if (data.isNotEmpty() && codigo.isNotEmpty()) {
                items.add(ProvaItem(data, codigo, link, tipo, conjunto, materia))
            }
        }

        adapter.setDadosOriginais(items)
        adapter.aplicarFiltro(filtroAtual)
    }

    private inner class ProvasAdapter(
        private var items: List<ProvaItem>,
        private val parentFragment: Fragment
    ) : RecyclerView.Adapter<ProvasAdapter.ViewHolder>() {

        private var dadosOriginais: List<ProvaItem> = items

        @SuppressLint("NotifyDataSetChanged")
        fun setDadosOriginais(dados: List<ProvaItem>) {
            dadosOriginais = dados
            aplicarFiltro(filtroAtual)
        }

        @SuppressLint("NotifyDataSetChanged")
        fun aplicarFiltro(filtro: Int) {
            items = when (filtro) {
                FILTRO_PROVAS -> dadosOriginais.filter { !it.tipo.contains("rec", ignoreCase = true) }
                FILTRO_RECUPERACOES -> dadosOriginais.filter { it.tipo.contains("rec", ignoreCase = true) }
                else -> dadosOriginais
            }
            notifyDataSetChanged()

            if (parentFragment.isAdded) {
                if (items.isEmpty()) {
                    txtSemProvas.visibility = View.VISIBLE
                    recyclerProvas.visibility = View.GONE
                } else {
                    txtSemProvas.visibility = View.GONE
                    recyclerProvas.visibility = View.VISIBLE
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_prova_calendar, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]

            holder.txtData.text = item.data
            holder.txtCodigo.text = item.codigo
            holder.txtConjunto.text = item.conjunto
            holder.txtMateria.text = item.materia

            val isRecuperacao = item.tipo.contains("rec", ignoreCase = true)
            val badgeText = if (isRecuperacao) item.tipo.uppercase() else "PROVA"

            holder.badgeTipo.text = badgeText

            if (isRecuperacao) {
                holder.badgeContainer.setBackgroundResource(R.drawable.bg_prova_recuperacao)
                holder.badgeTipo.setTextColor(ContextCompat.getColor(holder.badgeTipo.context, R.color.badge_text_recuperacao))
            } else {
                holder.badgeContainer.setBackgroundResource(R.drawable.bg_prova_normal)
                holder.badgeTipo.setTextColor(ContextCompat.getColor(holder.badgeTipo.context, R.color.badge_text_normal))
            }

            holder.card.setOnClickListener {
                if (parentFragment.isAdded) {
                    Log.d("CalendarioProvas", "Clique na prova: ${item.codigo}")

                    // Usar o método openCustomFragment da MainActivity em vez de transaction direto
                    val mainActivity = parentFragment.activity as? MainActivity
                    if (mainActivity != null) {
                        val materiaFragment = MateriadeProva.newInstance(item.link)
                        Log.d("CalendarioProvas", "Abrindo MateriadeProva para link: ${item.link}")
                        mainActivity.openCustomFragment(materiaFragment)
                    } else {
                        Log.e("CalendarioProvas", "MainActivity não encontrada")
                    }
                }
            }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val card: MaterialCardView = itemView.findViewById(R.id.card_prova)
            val txtData: TextView = itemView.findViewById(R.id.txt_data)
            val txtCodigo: TextView = itemView.findViewById(R.id.txt_codigo)
            val txtConjunto: TextView = itemView.findViewById(R.id.txt_conjunto)
            val txtMateria: TextView = itemView.findViewById(R.id.txt_materia)
            val badgeTipo: TextView = itemView.findViewById(R.id.badge_tipo)
            val badgeContainer: FrameLayout = itemView.findViewById(R.id.badge_tipo_container)
        }
    }

    private data class ProvaItem(
        val data: String,
        val codigo: String,
        val link: String,
        val tipo: String,
        val conjunto: String,
        val materia: String
    )

    private inner class CacheHelper(context: Context) {
        private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun salvarProvas(html: String, mes: Int) {
            prefs.edit {
                putString("$KEY_BASE$mes", html)
                remove("$KEY_SEM_PROVAS$mes")
            }
        }

        fun salvarMesSemProvas(mes: Int) {
            prefs.edit {
                putBoolean("$KEY_SEM_PROVAS$mes", true)
                remove("$KEY_BASE$mes")
            }
        }

        fun loadHtml(mes: Int): String? {
            return prefs.getString("$KEY_BASE$mes", null)
        }

        fun temProvas(mes: Int): Boolean {
            return prefs.contains("$KEY_BASE$mes")
        }

        fun mesSemProvas(mes: Int): Boolean {
            return prefs.getBoolean("$KEY_SEM_PROVAS$mes", false)
        }
    }
}
