/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.ui.fragments.asset;

import android.app.Activity;
import android.app.PendingIntent;
import android.arch.lifecycle.MutableLiveData;
import android.arch.lifecycle.ViewModelProviders;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.widget.NestedScrollView;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.jakewharton.rxbinding2.widget.RxTextView;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import io.multy.R;
import io.multy.api.MultyApi;
import io.multy.model.entities.EosAccountPriceRequest;
import io.multy.model.entities.wallet.Wallet;
import io.multy.storage.RealmManager;
import io.multy.ui.fragments.BaseFragment;
import io.multy.ui.fragments.dialogs.AddressActionsDialogFragment;
import io.multy.ui.fragments.dialogs.WalletChooserDialogFragment;
import io.multy.util.Constants;
import io.multy.util.CryptoFormatUtils;
import io.multy.util.NativeDataHelper;
import io.multy.util.analytics.Analytics;
import io.multy.viewmodels.WalletViewModel;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import okhttp3.ResponseBody;
import retrofit2.Response;

import static android.content.Intent.ACTION_SEND;

/**
 * Created by anschutz1927@gmail.com on 26.07.18.
 */
public class CreatePayableAssetFragment extends BaseFragment {

    public static final String TAG = CreatePayableAssetFragment.class.getSimpleName();

    @BindView(R.id.text_status)
    TextView textStatus;
    @BindView(R.id.edit_name)
    EditText inputName;
    @BindView(R.id.text_chain)
    TextView textChain;
    @BindView(R.id.text_ram)
    TextView textRam;
    @BindView(R.id.text_cpu)
    TextView textCpu;
    @BindView(R.id.text_net)
    TextView textNet;
    @BindView(R.id.image_currency)
    ImageView imageCurrency;
    @BindView(R.id.text_wallet_name)
    TextView textWalletName;
    @BindView(R.id.text_address)
    TextView textAddress;
    @BindView(R.id.button_create)
    TextView buttonCreate;
    @BindView(R.id.notification_warning)
    View notificationFounds;
    @BindView(R.id.text_warning_address)
    TextView textWarningAddress;
    @BindView(R.id.scroll_view)
    NestedScrollView scrollView;

    private WalletViewModel walletViewModel;
    MutableLiveData<String> ram = new MutableLiveData<>();
    MutableLiveData<String> cpu = new MutableLiveData<>();
    MutableLiveData<String> net = new MutableLiveData<>();
    MutableLiveData<Wallet> paymentWallet = new MutableLiveData<>();

    public static CreatePayableAssetFragment getInstance() {
        return new CreatePayableAssetFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_create_payable_asset, container, false);
        ButterKnife.bind(this, view);
        if (getActivity() != null) {
            int chainId = getActivity().getIntent().getIntExtra(Constants.CHAIN_ID, -1);
            if (chainId != -1 && chainId != NativeDataHelper.Blockchain.EOS.getValue() && getFragmentManager() != null) {
                CreateAssetFragment fragment = CreateAssetFragment.getInstance();
                getFragmentManager().beginTransaction()
                        .replace(R.id.container_main, fragment, CreateAssetFragment.TAG)
                        .commit();
            } else {
                walletViewModel = ViewModelProviders.of(getActivity()).get(WalletViewModel.class);
                initialize();
            }
        }
        return view;
    }

    @Override
    public void onResume() {
        if (paymentWallet.getValue() != null && !paymentWallet.getValue().isValid() && getActivity() != null) {
            Wallet wallet = RealmManager.getAssetsDao().getWalletById(getActivity().getIntent()
                    .getLongExtra(Constants.EXTRA_WALLET_ID, 0));
            paymentWallet.setValue(wallet);
        }
        super.onResume();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK && getActivity() != null) {
            if (requestCode == WalletChooserDialogFragment.REQUEST_WALLET_ID) {
                long walletId = data.getLongExtra(Constants.EXTRA_WALLET_ID, 0);
                getActivity().getIntent().putExtra(Constants.EXTRA_WALLET_ID, walletId);
                paymentWallet.setValue(RealmManager.getAssetsDao().getWalletById(walletId));
            } else if (requestCode == Constants.REQUEST_CODE_SET_CHAIN) {
//                chainNet = data.getIntExtra(Constants.CHAIN_NET, 0);
                getActivity().getIntent().putExtra(Constants.CHAIN_ID, data.getIntExtra(Constants.CHAIN_ID, 0));
                String chainCurrency = data.getStringExtra(Constants.CHAIN_NAME);
                walletViewModel.chainCurrency.setValue(chainCurrency);
            }
        }
    }

    private void initialize() {
        Disposable disposable = RxTextView.textChanges(inputName)
                .debounce(300, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
                .filter(this::checkNameLength)
                .observeOn(Schedulers.io())
                .map(this::createAccountAndWallet)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::setNameStatus, this::handleError);
        walletViewModel.addDisposable(disposable);
        buttonCreate.setText(R.string.create);
        textStatus.setEnabled(false);
        onChainName("EOS ∙ EOS");
        onWallet(null);
        subscribeToObservers();
    }

    private void subscribeToObservers() {
        walletViewModel.chainCurrency.observe(this, this::onChainName);
        paymentWallet.observe(this, this::onWallet);
        ram.setValue("150");
        cpu.setValue("36");
        net.setValue("224");
        ram.observe(this, this::onRamSet);
        cpu.observe(this, this::onCpuSet);
        net.observe(this, this::onNetSet);
    }

    private void onWallet(Wallet wallet) {
        if (wallet == null) {
            textAddress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            textAddress.setText(R.string.select_your_wallet);
            textWalletName.setVisibility(View.GONE);
            imageCurrency.setVisibility(View.GONE);
        } else {
            imageCurrency.setVisibility(View.VISIBLE);
            textAddress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            textWalletName.setVisibility(View.VISIBLE);
            textWalletName.setText(wallet.getWalletName());
            textAddress.setText(wallet.getActiveAddress().getAddress());
            imageCurrency.setImageResource(wallet.getIconResourceId());
            textWarningAddress.setText(wallet.getActiveAddress().getAddress());
        }
        printAccountCost();
    }

    private void onRamSet(String ram) {
        textRam.setText(ram);
    }

    private void onCpuSet(String cpu) {
        textCpu.setText(cpu);
    }

    private void onNetSet(String net) {
        textNet.setText(net);
    }

    private void onChainName(String chainName) {
        if (chainName != null) {
            textChain.setText(chainName);
        }
    }

    private boolean checkNameLength(CharSequence name) {
        if (name.length() == 12) {
            inputName.setEnabled(false);
            showProgressDialog();
            return true;
        }
        textStatus.setText("Please, input valid name");
        textStatus.setEnabled(false);
        printAccountCost();
        return false;
    }

    private boolean createAccountAndWallet(CharSequence name) throws InterruptedException {
        Thread.sleep(1500);
        return new Random().nextBoolean();
    }

    private void setNameStatus(Boolean nameStatus) {
        textStatus.setEnabled(nameStatus);
        textStatus.setText(nameStatus ? R.string.name_is_free : R.string.name_is_taken);
        inputName.setEnabled(true);
        dismissProgressDialog();
        printAccountCost();
    }

    private void printAccountCost() {
        if (paymentWallet.getValue() != null && textStatus.isEnabled()) {
            showProgressDialog();
            Disposable disposable = Observable.create((ObservableOnSubscribe<String>) emitter -> {
                EosAccountPriceRequest requestBody = new EosAccountPriceRequest(150, 36, 224);
                Response<ResponseBody> response = MultyApi.INSTANCE.getAccountPrice(requestBody).execute();
                JSONObject body = new JSONObject(response.body().string());
                String eosToEth = "0.016783399"; //todo remove hardcode
                BigDecimal precost = new BigDecimal(body.getString("price")).multiply(new BigDecimal(eosToEth));
                emitter.onNext(CryptoFormatUtils.FORMAT_ETH.format(precost));
            }).observeOn(AndroidSchedulers.mainThread())
                    .subscribeOn(Schedulers.io())
                    .subscribe(cost -> {
                        String costString = String.format(Locale.ENGLISH, getString(R.string.create_for_eth), cost);
                        buttonCreate.setText(costString);
                        if (paymentWallet.getValue().getAvailableBalanceNumeric().compareTo(new BigDecimal(cost)) < 0) {
                            buttonCreate.setEnabled(false);
                            notificationFounds.setVisibility(View.VISIBLE);
                            scrollPageDown();
                        } else {
                            buttonCreate.setEnabled(true);
                            notificationFounds.setVisibility(View.GONE);
                        }
                        dismissProgressDialog();
                    }, throwable -> {
                        throwable.printStackTrace();
                        buttonCreate.setText(R.string.create);
                        buttonCreate.setEnabled(false);
                        dismissProgressDialog();
                    });
            walletViewModel.addDisposable(disposable);
        } else if (buttonCreate.isEnabled() || notificationFounds.getVisibility() == View.VISIBLE) {
            buttonCreate.setEnabled(false);
            buttonCreate.setText(R.string.create);
            notificationFounds.setVisibility(View.GONE);
        }
    }

    private void scrollPageDown() {
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void handleError(Throwable error) {
        error.printStackTrace();
        inputName.setEnabled(true);
        dismissProgressDialog();
    }

    private IntentSender getIntentSender(Context context) {
        return PendingIntent.getBroadcast(context, 0, new Intent(context,
                AssetInfoFragment.SharingBroadcastReceiver.class), PendingIntent.FLAG_CANCEL_CURRENT)
                .getIntentSender();
    }

    private void handleClick(View view) {
        view.setEnabled(false);
        view.postDelayed(() -> view.setEnabled(true), 500);
    }

    @OnClick(R.id.button_chain)
    void onClickChain(View view) {
        handleClick(view);
        Analytics.getInstance(getActivity()).logCreateWalletChain();
        if (getActivity() != null) {
            hideKeyboard(getActivity());
            ChainChooserFragment fragment = (ChainChooserFragment) getActivity().getSupportFragmentManager()
                    .findFragmentByTag(ChainChooserFragment.TAG);
            if (fragment == null) {
                fragment = ChainChooserFragment.getInstance();
            }
            fragment.setTargetFragment(this, Constants.REQUEST_CODE_SET_CHAIN);
            fragment.setSelectedChain(walletViewModel.chainCurrency.getValue(), 194);
            getActivity().getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container_main, fragment).addToBackStack(ChainChooserFragment.TAG)
                    .commit();
        }
    }

    @OnClick(R.id.button_fiat)
    void onClickFiat(View view) {
        handleClick(view);
        if (getActivity() != null) {
            Analytics.getInstance(getActivity()).logCreateWalletFiatClick();
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

    @OnClick(R.id.button_ram)
    void onClickRam(View view) {
        handleClick(view);
    }

    @OnClick(R.id.button_cpu)
    void onClickCpu(View view) {
        handleClick(view);
    }

    @OnClick(R.id.button_net)
    void onClickNet(View view){
        handleClick(view);
    }

    @OnClick(R.id.button_wallet)
    void onClickWallet(View view) {
        handleClick(view);
        if (getFragmentManager() != null) {
            WalletChooserDialogFragment dialog = WalletChooserDialogFragment.getInstance(NativeDataHelper.Blockchain.ETH.getValue());
            dialog.setTargetFragment(this, WalletChooserDialogFragment.REQUEST_WALLET_ID);
            dialog.show(getFragmentManager(), WalletChooserDialogFragment.TAG);
        }
    }

    @OnClick(R.id.button_create)
    void onClickCreate(View view) {
        view.setEnabled(false);
    }

    @SuppressWarnings("ConstantConditions, NewApi")
    @OnClick(R.id.button_share)
    void onClickShareAddress(View view) {
        handleClick(view);
        String address = paymentWallet.getValue().getActiveAddress().getAddress();
        Intent sharingIntent = new Intent(ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, address);
        startActivity(Intent.createChooser(sharingIntent, getString(R.string.share),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 ? null : getIntentSender(view.getContext())));
        view.post(view::invalidate);
    }

    @SuppressWarnings("ConstantConditions")
    @OnClick(R.id.button_receive)
    void onClickAddressReceive(View view) {
        handleClick(view);
        Wallet wallet = paymentWallet.getValue();
        AddressActionsDialogFragment.getInstance(wallet.getActiveAddress().getAddress(), wallet.getCurrencyId(), wallet.getNetworkId(),
                wallet.getIconResourceId(), false).show(getFragmentManager(), AddressActionsDialogFragment.TAG);
    }

    @OnClick(R.id.button_exchange)
    void onClickExchange(View view) {
        handleClick(view);
    }

    @OnClick(R.id.button_cancel)
    void onClickCancel() {
        if (getActivity() != null) {
            getActivity().onBackPressed();
        }
    }
}
