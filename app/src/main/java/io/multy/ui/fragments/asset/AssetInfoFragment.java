/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.ui.fragments.asset;

import android.arch.lifecycle.ViewModelProviders;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.CollapsingToolbarLayout;
import android.support.v4.app.Fragment;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.samwolfand.oneprefs.Prefs;

import net.cachapa.expandablelayout.ExpandableLayout;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import io.multy.R;
import io.multy.api.MultyApi;
import io.multy.model.entities.TransactionHistory;
import io.multy.model.entities.wallet.Erc20Balance;
import io.multy.model.entities.wallet.Erc20Token;
import io.multy.model.entities.wallet.Wallet;
import io.multy.model.events.TransactionUpdateEvent;
import io.multy.model.responses.SingleWalletResponse;
import io.multy.storage.AssetsDao;
import io.multy.storage.RealmManager;
import io.multy.ui.activities.AssetActivity;
import io.multy.ui.activities.AssetRequestActivity;
import io.multy.ui.activities.AssetSendActivity;
import io.multy.ui.activities.SeedActivity;
import io.multy.ui.adapters.AssetTransactionsAdapter;
import io.multy.ui.adapters.EthTransactionsAdapter;
import io.multy.ui.fragments.AddressesFragment;
import io.multy.ui.fragments.BaseFragment;
import io.multy.ui.fragments.MultisigSettingsFragment;
import io.multy.ui.fragments.dialogs.AddressActionsDialogFragment;
import io.multy.ui.fragments.dialogs.DonateDialog;
import io.multy.util.Constants;
import io.multy.util.CryptoFormatUtils;
import io.multy.util.NativeDataHelper;
import io.multy.util.analytics.Analytics;
import io.multy.util.analytics.AnalyticsConstants;
import io.multy.viewmodels.WalletViewModel;
import io.realm.RealmList;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class AssetInfoFragment extends BaseFragment implements AppBarLayout.OnOffsetChangedListener {

    public static final String TAG = AssetInfoFragment.class.getSimpleName();

    @BindView(R.id.collapsing_toolbar)
    CollapsingToolbarLayout collapsingToolbarLayout;
    @BindView(R.id.recycler_view)
    RecyclerView recyclerView;
    @BindView(R.id.text_address)
    TextView textAddress;
    @BindView(R.id.button_addresses)
    View buttonAddresses;
    @BindView(R.id.text_wallet_name)
    TextView textWalletName;
    @BindView(R.id.swipe_refresh_layout)
    SwipeRefreshLayout swipeRefreshLayout;

    @BindView(R.id.text_balance)
    TextView textBalance;
    @BindView(R.id.text_balance_fiat)
    TextView textBalanceFiat;
    @BindView(R.id.text_pending_balance)
    TextView textPendingBalance;
    @BindView(R.id.text_pending_balance_fiat)
    TextView textPendingBalanceFiat;

    @BindView(R.id.container_pending)
    ExpandableLayout containerPending;
    @BindView(R.id.appbar_layout)
    AppBarLayout appBarLayout;

    @BindView(R.id.image_arrow)
    ImageView imageArrow;
    @BindView(R.id.text_operations_empty)
    TextView textEmpty;
    @BindView(R.id.text_operation_create)
    TextView textCreate;
    @BindView(R.id.button_warn)
    View buttonWarn;
    @BindView(R.id.container_dummy)
    View containerDummy;

    private WalletViewModel viewModel;
    private SharingBroadcastReceiver receiver = new SharingBroadcastReceiver();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View convertView = inflater.inflate(R.layout.fragment_asset_info, container, false);
        ButterKnife.bind(this, convertView);

        viewModel = ViewModelProviders.of(getActivity()).get(WalletViewModel.class);
        Wallet wallet = viewModel.getWallet(getActivity().getIntent().getLongExtra(Constants.EXTRA_WALLET_ID, -1));
        setBaseViewModel(viewModel);

        subscribeWalletUpdates();
        showWalletInfo(wallet);
        setAddressesVisibility(wallet.getCurrencyId());
        initSwipeRefresh();
        setButtonWarnVisibility();
//        setTransactionsState();

        Analytics.getInstance(getActivity()).logWalletLaunch(AnalyticsConstants.WALLET_SCREEN, viewModel.getChainId());
        return convertView;
    }

    private void setButtonWarnVisibility() {
        if (Prefs.getBoolean(Constants.PREF_BACKUP_SEED)) {
            buttonWarn.setVisibility(View.GONE);
            buttonWarn.getLayoutParams().height = 0;

//            buttonWarn.post(() -> {
//                ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) recyclerView.getLayoutParams();
//                final int margin = ((ConstraintLayout.LayoutParams) buttonWarn.getLayoutParams()).topMargin * 2;
//                params.topMargin = buttonWarn.getMeasuredHeight() + margin;
//                recyclerView.setLayoutParams(params);
//            });
        }
    }

    private void initSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener(() -> {
            Analytics.getInstance(getActivity()).logWallet(AnalyticsConstants.WALLET_PULL, viewModel.getChainId());
            refreshWallet();
        });
    }

    private void setAddressesVisibility(int chainId) {
        buttonAddresses.setVisibility(chainId == NativeDataHelper.Blockchain.BTC.getValue() ? View.VISIBLE : View.GONE);
    }

    private void subscribeWalletUpdates() {
        viewModel.rates.observe(this, currenciesRate -> updateBalanceViews());
        viewModel.transactionUpdate.observe(this, transactionUpdateEntity -> {
            new Handler().postDelayed(this::refreshWallet, 300);
        });
    }

    private void showWalletInfo(Wallet wallet) {
        textWalletName.setText(wallet.getWalletName());
        textAddress.setText(wallet.getActiveAddress().getAddress());
        updateBalanceViews();
    }

    private void updateBalanceViews() {
        Wallet wallet = viewModel.getWalletLive().getValue();
        if (wallet == null || !wallet.isValid()) {
            return;
        }
        if (wallet.isPending()) {
//            textBalance.setText(wallet.getAvailableBalanceLabel());
//            textBalanceFiat.setText(wallet.getAvailableFiatBalanceLabel());

            if (wallet.isIncoming()) {
                textBalance.setText(wallet.getAvailableBalanceLabel());
                textBalanceFiat.setText(wallet.getAvailableFiatBalanceLabel());
            } else {
                textBalance.setText(String.format("%d %s", 0, wallet.getCurrencyName()));
                textBalanceFiat.setText(String.format("%d %s", 0, wallet.getFiatString()));
            }

            containerPending.expand();
            if (wallet.getCurrencyId() == NativeDataHelper.Blockchain.BTC.getValue()) {
                textPendingBalance.setText(String.format("%s BTC", CryptoFormatUtils.satoshiToBtc(wallet.getBalanceNumeric().longValue())));
                textPendingBalanceFiat.setText(wallet.getFiatString() + CryptoFormatUtils.satoshiToUsd(wallet.getBalanceNumeric().longValue()));
            } else {
                textPendingBalance.setText(wallet.getEthWallet().getPendingBalanceLabel());
                textPendingBalanceFiat.setText(wallet.getEthWallet().getFiatPendingBalanceLabel());
                textPendingBalanceFiat.append(wallet.getFiatString());
            }
        } else {
            textBalance.setText(wallet.getBalanceLabel());
            textBalanceFiat.setText(wallet.getFiatBalanceLabel());

            containerPending.collapse();
        }

        //get and print erc20tokens
        if (wallet.getCurrencyId() == NativeDataHelper.Blockchain.ETH.getValue() &&
                wallet.getActiveAddress().getErc20Balance() != null && wallet.getActiveAddress().getErc20Balance().size() > 0) {
            checkForErc20TokensBalance(wallet.getActiveAddress().getErc20Balance(), wallet.getNetworkId());
        }
    }

    private void checkForErc20TokensBalance(RealmList<Erc20Balance> tokens, int networkId) {
        for (Erc20Balance token : tokens) {
            if (new BigInteger(token.getBalance()).compareTo(BigInteger.ZERO) > 0) {
                updateErc20Views(networkId == NativeDataHelper.NetworkId.ETH_MAIN_NET.getValue() ?
                        // if network is testnet or we do not have info about token,
                        // we send null and print default data
                        RealmManager.getSettingsDao().getErc20TokenInfo(token.getAddress()) : null, token.getBalance());
            }
        }
    }

    private void updateErc20Views(@Nullable Erc20Token erc20Token, String balance) {
        final String tokenCoinTicker = erc20Token == null ? "default ticker" : erc20Token.getTicker();
        final String tokenCoinName = erc20Token == null ? "default name" : erc20Token.getName();
        //todo notify views
    }

    private void refreshWallet() {
        swipeRefreshLayout.setRefreshing(false);
//        viewModel.isLoading.postValue(true);

        if (!viewModel.getWalletLive().getValue().isValid()) {
            viewModel.getWallet(getActivity().getIntent().getLongExtra(Constants.EXTRA_WALLET_ID, 0));
        }
        final int currencyId = viewModel.getWalletLive().getValue().getCurrencyId();
        final int networkId = viewModel.getWalletLive().getValue().getNetworkId();
        final long walletId = viewModel.getWalletLive().getValue().getId();

        Callback<SingleWalletResponse> callback = new Callback<SingleWalletResponse>() {
            @Override
            public void onResponse(Call<SingleWalletResponse> call, Response<SingleWalletResponse> response) {
                viewModel.isLoading.postValue(false);
                SingleWalletResponse body = response.body();
                if (response.isSuccessful() && body != null && body.getWallets() != null && body.getWallets().size() > 0) {
                    AssetsDao assetsDao = RealmManager.getAssetsDao();
                    assetsDao.saveWallet(body.getWallets().get(0));
                    viewModel.wallet.setValue(assetsDao.getWalletById(walletId));
                }
                Wallet wallet = viewModel.wallet.getValue();
                showWalletInfo(wallet);
                requestTransactions(wallet.getCurrencyId(), wallet.getNetworkId(), wallet.getIndex());
            }

            @Override
            public void onFailure(Call<SingleWalletResponse> call, Throwable t) {
                viewModel.isLoading.setValue(false);
                t.printStackTrace();
            }
        };
        if (viewModel.getWalletLive().getValue().isMultisig()) {
            final String inviteCode = viewModel.getWalletLive().getValue().getMultisigWallet().getInviteCode().toLowerCase();
            Wallet linkedWallet = RealmManager.getAssetsDao()
                    .getMultisigLinkedWallet(viewModel.getWalletLive().getValue().getMultisigWallet().getOwners());
            int linkedIndex = linkedWallet.getIndex();
            MultyApi.INSTANCE.getMultisigWalletVerbose(inviteCode, currencyId, networkId, Constants.ASSET_TYPE_ADDRESS_MULTISIG)
                    .enqueue(callback);
            Callback<SingleWalletResponse> verboseCallback = new Callback<SingleWalletResponse>() {
                @Override
                public void onResponse(@NonNull Call<SingleWalletResponse> call, @NonNull Response<SingleWalletResponse> response) {
                    SingleWalletResponse body = response.body();
                    if (response.isSuccessful() && body != null && body.getWallets() != null && body.getWallets().size() > 0) {
                        RealmManager.getAssetsDao().saveWallet(body.getWallets().get(0));
                    }
                }

                @Override
                public void onFailure(@NonNull Call<SingleWalletResponse> call, @NonNull Throwable t) {
                    t.printStackTrace();
                }
            };
            if (linkedIndex < 0) {
                MultyApi.INSTANCE.getWalletVerbose(linkedWallet.getActiveAddress().getAddress(),
                        currencyId, networkId, Constants.ASSET_TYPE_ADDRESS_IMPORTED).enqueue(verboseCallback);
            } else {
                MultyApi.INSTANCE.getWalletVerbose(linkedIndex, currencyId, networkId, Constants.ASSET_TYPE_ADDRESS_MULTY)
                        .enqueue(verboseCallback);
            }
        } else {
            final int walletIndex = viewModel.getWalletLive().getValue().getIndex();
            if (walletIndex < 0) {
                MultyApi.INSTANCE.getWalletVerbose(viewModel.getWalletLive().getValue().getActiveAddress().getAddress(),
                        currencyId, networkId, Constants.ASSET_TYPE_ADDRESS_IMPORTED).enqueue(callback);
            } else {
                MultyApi.INSTANCE.getWalletVerbose(walletIndex, currencyId, networkId, Constants.ASSET_TYPE_ADDRESS_MULTY)
                        .enqueue(callback);
            }
        }
    }

    public void setWalletName(String name) {
        textWalletName.setText(name);
    }

    private void requestTransactions(final int currencyId, final int networkId, final int walletIndex) {
        if (viewModel.getWalletLive().getValue().isMultisig() || walletIndex < 0) {
            viewModel.getMultisigTransactionsHistory(viewModel.getWalletLive().getValue().getCurrencyId(),
                    viewModel.getWalletLive().getValue().getNetworkId(),
                    viewModel.getWalletLive().getValue().getActiveAddress().getAddress(),
                    walletIndex < 0 ? Constants.ASSET_TYPE_ADDRESS_IMPORTED : Constants.ASSET_TYPE_ADDRESS_MULTISIG)
                    .observe(this, transactions -> onTransactions(transactions, currencyId));
        } else {
            viewModel.getTransactionsHistory(currencyId, networkId, walletIndex)
                    .observe(this, transactions -> onTransactions(transactions, currencyId));
        }
    }

    private void onTransactions(ArrayList<TransactionHistory> transactions, int currencyId) {
        if (transactions != null && !transactions.isEmpty()) {
            try {
                final long walletId = viewModel.wallet.getValue().isValid() ?
                        viewModel.wallet.getValue().getId() :
                        viewModel.getWallet(getActivity().getIntent().getLongExtra(Constants.EXTRA_WALLET_ID, 0)).getId();

                recyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
                if (currencyId == NativeDataHelper.Blockchain.BTC.getValue()) {
                    recyclerView.setAdapter(new AssetTransactionsAdapter(transactions, walletId));
                } else if (currencyId == NativeDataHelper.Blockchain.EOS.getValue()) {
//                        recyclerView.setAdapter(new EosTransactionsAdapter(transactions, walletId));
                } else {
                    recyclerView.setAdapter(new EthTransactionsAdapter(transactions, walletId));

                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        setTransactionsState();
    }

    private void setTransactionsState() {
        if (recyclerView.getAdapter() == null || recyclerView.getAdapter().getItemCount() == 0) {
//            swipeRefreshLayout.setEnabled(false);
            setNotificationsVisibility(View.VISIBLE);
            setToolbarScrollFlag(0);
        } else {
            swipeRefreshLayout.setEnabled(true);
            setNotificationsVisibility(View.GONE);
            setToolbarScrollFlag(3);
        }
    }

    private void setNotificationsVisibility(int visibility) {
        imageArrow.setVisibility(visibility);
        textEmpty.setVisibility(visibility);
        textCreate.setVisibility(visibility);
        containerDummy.setVisibility(visibility);
    }

    private void setToolbarScrollFlag(int flag) {
        AppBarLayout.LayoutParams params = (AppBarLayout.LayoutParams) collapsingToolbarLayout.getLayoutParams();
        params.setScrollFlags(flag);
    }

    private void showAddressAction() {
        Wallet wallet = viewModel.getWalletLive().getValue();
        if (wallet != null && wallet.isValid() && !TextUtils.isEmpty(wallet.getActiveAddress().getAddress())) {
            AddressActionsDialogFragment.getInstance(wallet.getActiveAddress().getAddress(), wallet.getCurrencyId(),
                    wallet.getNetworkId(), wallet.getIconResourceId(), false, () -> {
                        if (recyclerView.getAdapter() != null) {
                            recyclerView.getAdapter().notifyDataSetChanged();
                        }
                    }).show(getChildFragmentManager(), AddressActionsDialogFragment.TAG);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onTransactionUpdateEvent(TransactionUpdateEvent event) {
//        refreshWallet();
    }

    @Override
    public void onStart() {
        super.onStart();
        Wallet wallet = viewModel.getWalletLive().getValue();
        requestTransactions(wallet.getCurrencyId(), wallet.getNetworkId(), wallet.getIndex());
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() != null) {
            getActivity().registerReceiver(receiver, new IntentFilter());
        }

        if (recyclerView.getAdapter() != null) {
            recyclerView.getAdapter().notifyDataSetChanged();
        }

        viewModel.subscribeSocketsUpdate();
        appBarLayout.addOnOffsetChangedListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getActivity() != null) {
            try {
                getActivity().unregisterReceiver(receiver);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        viewModel.unsubscribeSocketsUpdate();
        appBarLayout.removeOnOffsetChangedListener(this);
    }

    @OnClick(R.id.button_send)
    void onClickSend() {
        Analytics.getInstance(getActivity()).logWallet(AnalyticsConstants.WALLET_SEND, viewModel.getChainId());
        Wallet wallet = viewModel.getWalletLive().getValue();
        if (wallet != null && wallet.getAvailableBalanceNumeric().compareTo(BigDecimal.ZERO) <= 0) {
            Toast.makeText(getActivity(), R.string.no_balance, Toast.LENGTH_SHORT).show();
            return;
        }
        if (wallet.isMultisig()) {
            Wallet linked = RealmManager.getAssetsDao().getMultisigLinkedWallet(wallet.getMultisigWallet().getOwners());
            if (linked == null || linked.getAvailableBalanceNumeric()
                    .compareTo(new BigDecimal(CryptoFormatUtils.ethToWei(String.valueOf("0.0001")))) <= 0) {
                viewModel.errorMessage.setValue(getString(R.string.not_enough_linked_balance));
                return;
            }
        }
        if (wallet.isPending()) {
            return;
        }

        startActivity(new Intent(getActivity(), AssetSendActivity.class)
                .addCategory(Constants.EXTRA_SENDER_ADDRESS)
                .putExtra(Constants.EXTRA_WALLET_ID, getActivity().getIntent().getLongExtra(Constants.EXTRA_WALLET_ID, 0)));
    }

    @OnClick(R.id.button_receive)
    void onClickReceive() {
        Analytics.getInstance(getActivity()).logWallet(AnalyticsConstants.WALLET_RECEIVE, viewModel.getChainId());
        startActivity(new Intent(getActivity(), AssetRequestActivity.class)
                .putExtra(Constants.EXTRA_WALLET_ID, getActivity().getIntent().getLongExtra(Constants.EXTRA_WALLET_ID, 0)));
    }

    @OnClick(R.id.button_exchange)
    void onClickExchange() {
        Analytics.getInstance(getActivity()).logWallet(AnalyticsConstants.WALLET_EXCHANGE, viewModel.getChainId());
//        Toast.makeText(this, R.string.not_implemented, Toast.LENGTH_SHORT).show();
//        DonationActivity.showDonation(this, Constants.DONATE_ADDING_EXCHANGE);
        DonateDialog.getInstance(Constants.DONATE_ADDING_EXCHANGE).show(getActivity().getSupportFragmentManager(), DonateDialog.TAG);
    }

    @OnClick(R.id.button_addresses)
    public void onClickAddresses() {
        Analytics.getInstance(getActivity()).logWallet(AnalyticsConstants.WALLET_ADDRESSES, viewModel.getChainId());
        if (viewModel.getWalletLive().getValue() != null) {
            ((AssetActivity) getActivity()).setFragment(R.id.container_full, AddressesFragment.newInstance(viewModel.getWalletLive().getValue().getId()));
        } else {
            Wallet wallet = viewModel.getWallet(getActivity().getIntent().getLongExtra(Constants.EXTRA_WALLET_ID, 0));
            ((AssetActivity) getActivity()).setFragment(R.id.container_full, AddressesFragment.newInstance(wallet.getId()));
        }
    }

    @Override
    public void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset) {
        swipeRefreshLayout.setEnabled(verticalOffset == 0);
    }

    @OnClick(R.id.options)
    public void onClickOptions() {
        Analytics.getInstance(getActivity()).logWallet(AnalyticsConstants.WALLET_SETTINGS, viewModel.getChainId());
        Fragment fragment = null;
        Wallet wallet = viewModel.getWalletLive().getValue();
        if (wallet.isMultisig()) {
            Wallet linkedWallet = RealmManager.getAssetsDao().getMultisigLinkedWallet(wallet.getMultisigWallet().getOwners());
            fragment = MultisigSettingsFragment.newInstance(wallet, linkedWallet);
        } else {
            fragment = AssetSettingsFragment.newInstance();
        }
        ((AssetActivity) getActivity()).setFragment(R.id.container_full, fragment);
    }

    @OnClick(R.id.button_share)
    public void onClickShare() {
        Analytics.getInstance(getActivity()).logWallet(AnalyticsConstants.WALLET_SHARE, viewModel.getChainId());
        showAddressAction();
    }

    @OnClick(R.id.button_copy)
    public void onClickCopy() {
        Analytics.getInstance(getActivity()).logWallet(AnalyticsConstants.WALLET_ADDRESS, viewModel.getChainId());
        showAddressAction();
    }

    @OnClick(R.id.close)
    public void onClickClose() {
        Analytics.getInstance(getActivity()).logWallet(AnalyticsConstants.BUTTON_CLOSE, viewModel.getChainId());
        getActivity().finish();
    }

    @OnClick(R.id.button_warn)
    public void onClickWarn() {
        Analytics.getInstance(getActivity()).logWalletBackup(AnalyticsConstants.WALLET_BACKUP_SEED);
        startActivity(new Intent(getActivity(), SeedActivity.class));
    }

    public static class SharingBroadcastReceiver extends BroadcastReceiver {

        public SharingBroadcastReceiver() {
            super();
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getExtras() != null && intent.getExtras().get(Intent.EXTRA_CHOSEN_COMPONENT) != null) {
                String component = intent.getExtras().get(Intent.EXTRA_CHOSEN_COMPONENT).toString();
                String packageName = component.substring(component.indexOf("{") + 1, component.indexOf("/"));
                Analytics.getInstance(context).logWalletSharing(intent.getIntExtra(context.getString(R.string.chain_id), 1), packageName);
            }
        }
    }
}
