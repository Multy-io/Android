/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.ui.activities;

import android.arch.lifecycle.ViewModelProviders;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.IdRes;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.widget.Toast;

import java.math.BigDecimal;
import java.util.List;

import butterknife.ButterKnife;
import butterknife.OnClick;
import io.multy.R;
import io.multy.model.entities.wallet.Wallet;
import io.multy.ui.fragments.AddressesFragment;
import io.multy.ui.fragments.asset.AssetInfoFragment;
import io.multy.ui.fragments.asset.EthAssetInfoFragment;
import io.multy.ui.fragments.asset.TransactionInfoFragment;
import io.multy.ui.fragments.dialogs.DonateDialog;
import io.multy.util.Constants;
import io.multy.util.NativeDataHelper;
import io.multy.util.analytics.Analytics;
import io.multy.util.analytics.AnalyticsConstants;
import io.multy.viewmodels.WalletViewModel;

public class AssetActivity extends BaseActivity {

    private boolean isFirstFragmentCreation;
    private WalletViewModel viewModel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_asset_info);
        ButterKnife.bind(this);
        isFirstFragmentCreation = true;

        viewModel = ViewModelProviders.of(this).get(WalletViewModel.class);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        Wallet wallet = viewModel.getWallet(getIntent().getLongExtra(Constants.EXTRA_WALLET_ID, 0));
        if (wallet.getCurrencyId() == NativeDataHelper.Blockchain.BTC.getValue()) {
            setFragment(R.id.frame_container, AssetInfoFragment.newInstance());
        } else if (wallet.getCurrencyId() == NativeDataHelper.Blockchain.ETH.getValue()) {
            setFragment(R.id.frame_container, EthAssetInfoFragment.newInstance());
        }
    }

    @OnClick(R.id.send)
    void onClickSend() {
        Analytics.getInstance(this).logWallet(AnalyticsConstants.WALLET_SEND, viewModel.getChainId());
        if (viewModel.getWalletLive().getValue() != null &&
                viewModel.getWalletLive().getValue().getAvailableBalanceNumeric().compareTo(BigDecimal.ZERO) <= 0) {
            Toast.makeText(this, R.string.no_balance, Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, AssetSendActivity.class)
                .addCategory(Constants.EXTRA_SENDER_ADDRESS)
                .putExtra(Constants.EXTRA_WALLET_ID, getIntent().getLongExtra(Constants.EXTRA_WALLET_ID, 0)));
    }

    @OnClick(R.id.receive)
    void onClickReceive() {
        Analytics.getInstance(this).logWallet(AnalyticsConstants.WALLET_RECEIVE, viewModel.getChainId());
        startActivity(new Intent(this, AssetRequestActivity.class)
                .putExtra(Constants.EXTRA_WALLET_ID, getIntent().getLongExtra(Constants.EXTRA_WALLET_ID, 0)));
    }

    @OnClick(R.id.exchange)
    void onClickExchange() {
        Analytics.getInstance(this).logWallet(AnalyticsConstants.WALLET_EXCHANGE, viewModel.getChainId());
//        Toast.makeText(this, R.string.not_implemented, Toast.LENGTH_SHORT).show();
//        DonationActivity.showDonation(this, Constants.DONATE_ADDING_EXCHANGE);
        DonateDialog.getInstance(Constants.DONATE_ADDING_EXCHANGE).show(getSupportFragmentManager(), DonateDialog.TAG);
    }

    public void setFragment(@IdRes int container, Fragment fragment) {
        FragmentTransaction transaction = getSupportFragmentManager()
                .beginTransaction()
                .replace(container, fragment);

        if (!isFirstFragmentCreation) {
            transaction.addToBackStack(fragment.getClass().getName());
        }

        isFirstFragmentCreation = false;
        transaction.commit();
    }

    @Override
    public void onBackPressed() {
        logCancel();
        super.onBackPressed();
    }

    private void logCancel() {
        List<Fragment> backStackFragments = getSupportFragmentManager().getFragments();
        for (Fragment backStackFragment : backStackFragments) {
            if (backStackFragment instanceof AddressesFragment && backStackFragment.isVisible()) {
                Analytics.getInstance(this).logWalletAddresses(AnalyticsConstants.BUTTON_CLOSE, viewModel.getChainId());
                break;
//            } else if (backStackFragment instanceof AssetSettingsFragment && backStackFragment.isVisible()) {
//                Analytics.getInstance(this).logWalletSettings(AnalyticsConstants.BUTTON_CLOSE, viewModel.getChainId());
//                break;
            } else if (backStackFragment instanceof TransactionInfoFragment && backStackFragment.isVisible()) {
                Analytics.getInstance(this).logWalletTransaction(AnalyticsConstants.BUTTON_CLOSE, viewModel.getChainId());
                break;
            } else if (backStackFragment instanceof AssetInfoFragment && backStackFragment.isVisible()) {
                Analytics.getInstance(this).logWallet(AnalyticsConstants.BUTTON_CLOSE, viewModel.getChainId());
                break;
            }
        }
    }


}
