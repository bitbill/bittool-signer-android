package com.bittool.signer.feature.settings.compatibility

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.CompatibleWallet
import com.bittool.signer.core.model.CompatibleWallets
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** The two segments of Compatibility (iOS `CompatibilityView.Tab`). Formats is the default. */
enum class CompatibilityTab { Formats, Wallets }

/** One chain section of the Formats segment (iOS `item(_:formats:signing:)`, `SettingsTab.swift:199-218`). */
@Immutable
data class ChainFormats(
    val chain: Chain,
    /** Wallets that watch and sign by QR (`CompatibleWallets.all(chain)`, signing lists first, de-duplicated). */
    val signers: List<CompatibleWallet>,
    val watchers: List<CompatibleWallet>,
)

/**
 * Settings › Compatibility (iOS `CompatibilityView` + `WalletDirectoryView`, activity-settings.md §6): the open QR
 * formats per chain with the wallets documented to read them, and the alphabetical wallet directory. The data is the
 * bundled catalogue (`CompatibleWallets`); only the selected segment is state, kept across process death.
 */
@HiltViewModel
class CompatibilityViewModel @Inject constructor(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    val tab: StateFlow<CompatibilityTab> = savedStateHandle.getStateFlow(TAB, CompatibilityTab.Formats)

    /** Bitcoin, EVM, TRON, Solana in this order. */
    val chains: List<ChainFormats> = Chain.entries.map { chain ->
        val wallets = CompatibleWallets.all(chain)
        ChainFormats(
            chain = chain,
            signers = wallets.filter { it.support == CompatibleWallet.Support.Signing },
            watchers = wallets.filter { it.support == CompatibleWallet.Support.WatchOnly },
        )
    }

    /** Wallet Level ("Works with"). */
    val walletLevel: List<CompatibleWallet> = CompatibleWallets.walletLevel

    /** Every listed wallet once, signing wins, alphabetical (`CompatibleWallets.directory`). */
    val directory: List<CompatibleWallet> = CompatibleWallets.directory

    fun onTabSelected(tab: CompatibilityTab) {
        savedStateHandle[TAB] = tab
    }

    private companion object {
        const val TAB = "tab"
    }
}
