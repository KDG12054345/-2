package com.faust.presentation.view

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.faust.R
import com.faust.models.AppInfo
import com.faust.models.BlockedApp
import com.faust.presentation.viewmodel.AppSelectionViewModel
import com.faust.utils.AppCategoryUtils
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch

class AppSelectionDialog(
    private val onAppSelected: (BlockedApp) -> Unit
) : DialogFragment() {

    private val viewModel: AppSelectionViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory.getInstance(requireActivity().application)
    }
    private lateinit var appListAdapter: InstalledAppAdapter
    private lateinit var chipGroupCategories: ChipGroup

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_app_selection, null)

        // ChipGroup 초기화
        chipGroupCategories = view.findViewById(R.id.chipGroupCategories)
        setupCategoryChips()

        // RecyclerView 및 어댑터 초기화
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerViewApps)
        appListAdapter = InstalledAppAdapter { app ->
            onAppSelected(app.toBlockedApp())
            dismiss()
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = appListAdapter

        // ViewModel의 필터링된 앱 목록 관찰
        observeFilteredApps()

        return AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.select_app))
            .setView(view)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
    }

    /**
     * 카테고리 칩들을 설정하고 클릭 리스너를 연결합니다.
     */
    private fun setupCategoryChips() {
        chipGroupCategories.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedChipId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            
            val category = when (checkedChipId) {
                R.id.chipAll -> AppCategoryUtils.CATEGORY_ALL
                R.id.chipSocial -> AppCategoryUtils.CATEGORY_SOCIAL
                R.id.chipGame -> AppCategoryUtils.CATEGORY_GAME
                R.id.chipVideo -> AppCategoryUtils.CATEGORY_VIDEO
                R.id.chipBrowser -> AppCategoryUtils.CATEGORY_BROWSER
                R.id.chipOther -> AppCategoryUtils.CATEGORY_OTHER
                else -> AppCategoryUtils.CATEGORY_ALL
            }
            
            viewModel.filterAppsByCategory(category)
        }
    }

    /**
     * ViewModel의 필터링된 앱 목록을 관찰하여 어댑터에 업데이트합니다.
     */
    private fun observeFilteredApps() {
        lifecycleScope.launch {
            viewModel.filteredApps.collect { apps ->
                appListAdapter.submitList(apps)
            }
        }
    }
}

class InstalledAppAdapter(
    private val onAppClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<InstalledAppAdapter.ViewHolder>() {

    private val apps = mutableListOf<AppInfo>()

    fun submitList(newApps: List<AppInfo>) {
        apps.clear()
        apps.addAll(newApps)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun getItemCount(): Int = apps.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text1: TextView = itemView.findViewById(android.R.id.text1)
        private val text2: TextView = itemView.findViewById(android.R.id.text2)

        fun bind(app: AppInfo) {
            text1.text = app.appName
            text2.text = app.packageName

            itemView.setOnClickListener {
                onAppClick(app)
            }
        }
    }
}
