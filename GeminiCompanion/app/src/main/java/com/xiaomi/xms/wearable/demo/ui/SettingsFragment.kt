package com.xiaomi.xms.wearable.demo.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import com.xiaomi.xms.wearable.demo.ResponseStorage
import com.xiaomi.xms.wearable.demo.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()

        // 1. Загружаем сохраненные значения
        binding.KeyInput2.setText(ResponseStorage.getApiKey(context))   // Ключ 1
        binding.KeyInput.setText(ResponseStorage.getApiKey2(context))   // Ключ 2
        binding.PromptInput.setText(ResponseStorage.getCustomPrompt(context))

        // 2. Автосохранение Ключа 1
        binding.KeyInput2.doOnTextChanged { text, _, _, _ ->
            ResponseStorage.saveApiKey(context, text.toString())
        }

        // 3. Автосохранение Ключа 2
        binding.KeyInput.doOnTextChanged { text, _, _, _ ->
            ResponseStorage.saveApiKey2(context, text.toString())
        }

        // 4. Автосохранение промпта
        binding.PromptInput.doOnTextChanged { text, _, _, _ ->
            ResponseStorage.saveCustomPrompt(context, text.toString())
        }

        // 5. Полный сброс истории, логов и фото по кнопке
        binding.btnResetHistory.setOnClickListener {
            ResponseStorage.clearAllData(context)
            Toast.makeText(context, "История очищена", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}