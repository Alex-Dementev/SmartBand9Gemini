package com.xiaomi.xms.wearable.demo.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.xiaomi.xms.wearable.demo.ResponseStorage
import com.xiaomi.xms.wearable.demo.databinding.FragmentDbBinding

class DatabaseFragment : Fragment() {

    private var _binding: FragmentDbBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDbBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() {
        val context = requireContext()

        // 1. Загрузка истории диалога
        val history = ResponseStorage.getChatHistory(context)
        if (history.isNotBlank()) {
            binding.AllHistory.text = history
        } else {
            binding.AllHistory.text = "Здесь пока нет истории."
        }

        // 2. Загрузка системных логов
        val logs = ResponseStorage.getSystemLogs(context)
        if (logs.isNotBlank()) {
            binding.AllTrablesHistory.text = logs
        } else {
            binding.AllTrablesHistory.text = "Системные логи отсутствуют."
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}