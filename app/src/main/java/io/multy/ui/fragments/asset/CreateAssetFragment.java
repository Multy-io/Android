/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.ui.fragments.asset;

import android.app.Activity;
import android.arch.lifecycle.ViewModelProviders;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import com.samwolfand.oneprefs.Prefs;

import org.json.JSONObject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import io.multy.R;
import io.multy.api.MultyApi;
import io.multy.model.entities.wallet.Wallet;
import io.multy.model.responses.SingleWalletResponse;
import io.multy.storage.RealmManager;
import io.multy.ui.activities.AssetActivity;
import io.multy.ui.fragments.BaseFragment;
import io.multy.util.Constants;
import io.multy.util.NativeDataHelper;
import io.multy.util.analytics.Analytics;
import io.multy.viewmodels.WalletViewModel;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class CreateAssetFragment extends BaseFragment {

    public static final String TAG = CreateAssetFragment.class.getSimpleName();

    @BindView(R.id.edit_name)
    EditText editTextWalletName;
    @BindView(R.id.text_create)
    TextView textViewCreateWallet;
    @BindView(R.id.text_fiat_currency)
    TextView textViewFiatCurrency;
    @BindView(R.id.text_chain_currency)
    TextView textViewChainCurrency;

    private WalletViewModel walletViewModel;
    private int chainNet = NativeDataHelper.NetworkId.MAIN_NET.getValue();
    private int chainId = NativeDataHelper.Blockchain.BTC.getValue();

    public static CreateAssetFragment getInstance() {
        return new CreateAssetFragment();
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        getActivity().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        View v = inflater.inflate(R.layout.view_assets_action_add, container, false);
        ButterKnife.bind(this, v);
        if (chainId == NativeDataHelper.Blockchain.EOS.getValue() && getFragmentManager() != null) {
            getActivity().getIntent().putExtra(Constants.CHAIN_ID, chainId);
            CreatePayableAssetFragment fragment = CreatePayableAssetFragment.getInstance();
            getFragmentManager().beginTransaction()
                    .replace(R.id.container_main, fragment, CreatePayableAssetFragment.TAG)
                    .commit();
        } else {
            initialize();
            subscribeToCurrencyUpdate();
            Analytics.getInstance(getActivity()).logCreateWalletLaunch();

            editTextWalletName.requestFocus();
            editTextWalletName.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm.isActive()) {
                    imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
                }

                imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
            }, 100);
        }
        return v;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == Constants.REQUEST_CODE_SET_CHAIN) {
            if (resultCode == Activity.RESULT_OK && data.getExtras() != null) {
                chainNet = data.getExtras().getInt(Constants.CHAIN_NET, 0);
                chainId = data.getExtras().getInt(Constants.CHAIN_ID, 0);
                String chainCurrency = data.getExtras().getString(Constants.CHAIN_NAME, "");
//                if (chainNet == NativeDataHelper.NetworkId.TEST_NET.getValue() ||
//                        chainNet == NativeDataHelper.NetworkId.RINKEBY.getValue()) {
//                    chainCurrency = chainCurrency.concat(" Testnet");
//                }
                walletViewModel.chainCurrency.setValue(chainCurrency);
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void subscribeToCurrencyUpdate() {
        walletViewModel = ViewModelProviders.of(getActivity()).get(WalletViewModel.class);
        setBaseViewModel(walletViewModel);
        walletViewModel.fiatCurrency.observe(this, s -> {
            //TODO update wallet fiat currency
            textViewFiatCurrency.setText(s);
        });
        walletViewModel.chainCurrency.observe(this, s -> {
            //TODO update wallet chain currency
            textViewChainCurrency.setText(s);
        });
    }

    private void initialize() {
        editTextWalletName.addTextChangedListener(getEditWatcher());
    }

    private TextWatcher getEditWatcher() {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (editable.length() > 0) {
                    if (textViewChainCurrency.getText().length() > 0 && textViewFiatCurrency.getText().length() > 0) {
                        textViewCreateWallet.setEnabled(true);
                        textViewCreateWallet.setBackgroundColor(Color.parseColor("#FF459FF9"));
                    }
                } else {
                    textViewCreateWallet.setEnabled(false);
                    textViewCreateWallet.setBackgroundColor(Color.parseColor("#BEC8D2"));
                }
            }
        };
    }

    private void showWalletInfoActivity(Wallet walletRealmObject) {
        Intent intent = new Intent(getActivity(), AssetActivity.class);
        if (walletRealmObject != null) {
            intent.putExtra(Constants.EXTRA_WALLET_ID, walletRealmObject.getId());
        }

        getActivity().startActivity(intent);
        getActivity().finish();
    }

    @OnClick(R.id.button_chain)
    public void onClickChain() {
        Analytics.getInstance(getActivity()).logCreateWalletChain();
        if (getActivity() != null) {
            hideKeyboard(getActivity());
            ChainChooserFragment fragment = (ChainChooserFragment) getActivity().getSupportFragmentManager()
                    .findFragmentByTag(ChainChooserFragment.TAG);
            if (fragment == null) {
                fragment = ChainChooserFragment.getInstance();
            }
            fragment.setTargetFragment(CreateAssetFragment.this, Constants.REQUEST_CODE_SET_CHAIN);
            fragment.setSelectedChain(textViewChainCurrency.getText().toString().split("\\*")[0], chainNet);
            getActivity().getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container_main, fragment).addToBackStack(ChainChooserFragment.TAG)
                    .commit();
        }
    }

    @OnClick(R.id.button_fiat)
    public void onClickFiat() {
        Analytics.getInstance(getActivity()).logCreateWalletFiatClick();

        if (getActivity() != null) {
            hideKeyboard(getActivity());
            CurrencyChooserFragment fragment = (CurrencyChooserFragment) getActivity().getSupportFragmentManager()
                    .findFragmentByTag(CurrencyChooserFragment.TAG);
            if (fragment == null) {
                fragment = CurrencyChooserFragment.getInstance();
            }
            getActivity().getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container_main, fragment).addToBackStack(ChainChooserFragment.TAG)
                    .commit();
        }
    }

    private void loadCreatedWallet(int currencyId, int networkId) {
        final int walletIndex = currencyId == NativeDataHelper.Blockchain.BTC.getValue() ?
                Prefs.getInt(Constants.PREF_WALLET_TOP_INDEX_BTC + networkId, 0) :
                Prefs.getInt(Constants.PREF_WALLET_TOP_INDEX_ETH + networkId, 0);
        MultyApi.INSTANCE.getWalletVerbose(walletIndex, currencyId, networkId).enqueue(new Callback<SingleWalletResponse>() {
            @Override
            public void onResponse(Call<SingleWalletResponse> call, Response<SingleWalletResponse> response) {
                walletViewModel.isLoading.setValue(false);
                if (response.isSuccessful() && response.body().getWallets() != null && response.body().getWallets().size() > 0) {
                    RealmManager.getAssetsDao().saveWallet(response.body().getWallets().get(0));
                    Wallet wallet = RealmManager.getAssetsDao().getWallet(currencyId, networkId, walletIndex);
                    showWalletInfoActivity(wallet);
                }
            }

            @Override
            public void onFailure(Call<SingleWalletResponse> call, Throwable t) {
                t.printStackTrace();
                walletViewModel.errorMessage.setValue(t.getLocalizedMessage());
                walletViewModel.isLoading.setValue(false);
            }
        });
    }

    @OnClick(R.id.text_create)
    public void onClickCreate() {
        walletViewModel.isLoading.setValue(true);
        Analytics.getInstance(getActivity()).logCreateWallet();
        Wallet walletRealmObject = walletViewModel.createWallet(editTextWalletName.getText().toString(), chainId, chainNet);
        MultyApi.INSTANCE.addWallet(getActivity(), walletRealmObject).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> response) {
                if (response.isSuccessful()) {
                    try {
                        String body = response.body().string();
                        long dateOfCreation = new JSONObject(body).getLong("time");
                        walletRealmObject.setDateOfCreation(dateOfCreation);
                        RealmManager.getAssetsDao().saveWallet(walletRealmObject);
                        showWalletInfoActivity(walletRealmObject);
                    } catch (Throwable t) {
                        t.printStackTrace();
                        loadCreatedWallet(chainId, chainNet);
                    }
                } else {
                    walletViewModel.errorMessage.setValue(getString(R.string.something_went_wrong));
                    walletViewModel.isLoading.setValue(false);
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                walletViewModel.isLoading.setValue(false);
                walletViewModel.errorMessage.setValue(t.getLocalizedMessage());
                t.printStackTrace();
            }
        });
    }

    @OnClick(R.id.text_cancel)
    void onCancelClick() {
        Analytics.getInstance(getActivity()).logCreateWalletClose();
        getActivity().finish();
    }
}
