/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.ui.activities;

import android.app.Dialog;
import android.arch.lifecycle.ViewModelProviders;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import com.crashlytics.android.Crashlytics;
import com.github.guilhe.circularprogressview.CircularProgressView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

import butterknife.BindColor;
import butterknife.BindDrawable;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import io.multy.Multy;
import io.multy.R;
import io.multy.api.MultyApi;
import io.multy.model.entities.Estimation;
import io.multy.model.entities.MultisigFactory;
import io.multy.model.entities.wallet.Owner;
import io.multy.model.entities.wallet.Wallet;
import io.multy.model.entities.wallet.WalletPrivateKey;
import io.multy.model.requests.HdTransactionRequestEntity;
import io.multy.model.responses.ServerConfigResponse;
import io.multy.storage.RealmManager;
import io.multy.ui.adapters.OwnersAdapter;
import io.multy.ui.fragments.MultisigSettingsFragment;
import io.multy.ui.fragments.ScanInvitationCodeFragment;
import io.multy.ui.fragments.ShareMultisigFragment;
import io.multy.ui.fragments.dialogs.CompleteDialogFragment;
import io.multy.ui.fragments.dialogs.SimpleDialogFragment;
import io.multy.util.Constants;
import io.multy.util.CryptoFormatUtils;
import io.multy.util.NativeDataHelper;
import io.multy.util.analytics.Analytics;
import io.multy.viewmodels.CreateMultisigViewModel;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import timber.log.Timber;

import static io.multy.ui.fragments.send.SendSummaryFragment.byteArrayToHex;

public class CreateMultiSigActivity extends BaseActivity {

    @BindView(R.id.progress)
    CircularProgressView circularProgressView;
    @BindView(R.id.text_title)
    TextView textTitle;
    @BindView(R.id.text_status)
    TextView textStatus;
    @BindView(R.id.text_action)
    TextView textAction;
    @BindView(R.id.image_action)
    ImageView imageAction;
    @BindView(R.id.recycler_view)
    RecyclerView recyclerView;
    @BindView(R.id.text_count)
    TextView textCount;
    @BindView(R.id.text_owners)
    TextView textOwners;
    @BindView(R.id.container_header)
    View header;
    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.button_action)
    View buttonAction;
    @BindDrawable(R.drawable.ic_pending_small_clock)
    Drawable waitingDrawable;
    @BindDrawable(R.drawable.ic_ready_to_start)
    Drawable readyDrawable;
    @BindColor(R.color.green_light)
    int colorGreen;
    @BindColor(R.color.colorPrimaryDark)
    int colorBlue;
    @BindColor(R.color.green_white)
    int textColorReady;
    @BindColor(R.color.blue_white)
    int textColorWaiting;

    private OwnersAdapter ownersAdapter;
    private CreateMultisigViewModel viewModel;
    private Dialog progressDialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_multisig);
        ButterKnife.bind(this);
        viewModel = ViewModelProviders.of(this).get(CreateMultisigViewModel.class);
        viewModel.isLoading.observe(this, this::onProgress);
        viewModel.errorMessage.observe(this, this::onError);
        if (getIntent().getBooleanExtra(Constants.EXTRA_SCAN, false)) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.root, ScanInvitationCodeFragment.newInstance())
                    .commit();
        } else /*if (getIntent().getBooleanExtra(Constants.EXTRA_CREATE, false))*/ {
            viewModel.setInviteCode(getIntent().getStringExtra(Constants.EXTRA_INVITE_CODE));
            viewModel.setWalletId(getIntent().getExtras().getLong(Constants.EXTRA_WALLET_ID));
            viewModel.updateMultisigWallet();
        }
        viewModel.getMultisigWallet().observe(this, this::onMultisigWallet);
        viewModel.updateLinkedWallet();
        initList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.connectSockets(args -> {
            viewModel.updateMultisigWallet();
        });
    }

    @Override
    protected void onPause() {
        viewModel.disconnectSockets();
        super.onPause();
    }

    private void showError(Throwable throwable) {
        viewModel.isLoading.postValue(false);
        if (throwable.getMessage().contains("Transaction is trying to spend more than available") ||
                throwable.getMessage().contains("insufficient funds")) {
            viewModel.errorMessage.setValue(getString(R.string.not_enough_balance));
        } else {
            if (throwable.getMessage().contains("nonce too low")) {
                viewModel.updateMultisigWallet();
            }
            viewModel.errorMessage.setValue(getString(R.string.error_sending_tx));
        }
        throwable.printStackTrace();
        Crashlytics.logException(throwable);
    }

    private void onError(String message) {
        SimpleDialogFragment dialog = SimpleDialogFragment
                .newInstanceNegative(getString(R.string.error), message, null);
        dialog.show(getSupportFragmentManager(), "");
    }

    private void onMultisigWallet(Wallet wallet) {
        if (wallet != null) {
            if (wallet.getMultisigWallet().getDeployStatus() >= Constants.DEPLOY_STATUS_PENDING /*&& !viewModel.isCreator()*/) {
                CompleteDialogFragment.newInstance(wallet.getCurrencyId()).show(getSupportFragmentManager(), "");
            } else {
                fillViews();
            }
        } else if (viewModel.getInviteCode().getValue() != null) {
            onBackPressed();
        }
    }

    private void initList() {
        ownersAdapter = new OwnersAdapter(v -> {
            if (viewModel.isCreator()) {
                showKickDialog((String) v.getTag());
                return true;
            }
            return false;
        });
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(ownersAdapter);
    }

    private void showKickDialog(String addressToKick) {
        if (viewModel.getLinkedWallet().getValue() != null &&
                !viewModel.getLinkedWallet().getValue().getActiveAddress().getAddress().equals(addressToKick)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.delete_user)
                    .setPositiveButton(R.string.yes, (dialog, which) -> viewModel.kickUser(addressToKick))
                    .setNegativeButton(R.string.no, (dialog, which) -> dialog.cancel())
                    .show();
        }
    }

    private void fillViews() {
        Wallet multisigWallet = viewModel.getMultisigWallet().getValue();
        textTitle.setText(multisigWallet.getWalletName());
        textCount.setText(String.format(Locale.ENGLISH, "%d / %d", multisigWallet.getMultisigWallet().getOwners().size(),
                multisigWallet.getMultisigWallet().getOwnersCount()));
        circularProgressView.setMax(multisigWallet.getMultisigWallet().getOwnersCount());
        circularProgressView.setProgress(multisigWallet.getMultisigWallet().getOwners().size(), true);

        StringBuilder stringBuilder = new StringBuilder();
        ArrayList<Owner> owners = new ArrayList<>(multisigWallet.getMultisigWallet().getOwnersCount());
        Owner owner;
        for (int i = 0; i < multisigWallet.getMultisigWallet().getOwnersCount(); i++) {
            if (i < multisigWallet.getMultisigWallet().getOwners().size()) {
                owner = multisigWallet.getMultisigWallet().getOwners().get(i);
                stringBuilder.append(owner.getAddress());
                if (i != multisigWallet.getMultisigWallet().getOwnersCount() - 1) {
                    stringBuilder.append("\n");
                }
            } else {
                owner = null;
            }
            owners.add(owner);
        }

        ownersAdapter.setOwners(owners);
        textOwners.setText(stringBuilder.toString());

        if (viewModel.isCreator()) {
            buttonAction.setVisibility(View.VISIBLE);
            textAction.setText(R.string.invitation_code);
        }
        if (multisigWallet.getMultisigWallet().getOwners().size() == multisigWallet.getMultisigWallet().getOwnersCount()) {
            textStatus.setText(R.string.ready_to_start);
            textStatus.setTextColor(textColorReady);
            textStatus.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_ready_to_start, 0, 0, 0);
            toolbar.setBackground(new ColorDrawable(colorGreen));
            header.setBackgroundColor(colorGreen);
            if (viewModel.isCreator()) {
                MultyApi.INSTANCE.getEstimations("price").enqueue(new Callback<Estimation>() {
                    @Override
                    public void onResponse(@NonNull Call<Estimation> call, @NonNull Response<Estimation> response) {
                        if (response.isSuccessful()) {
                            final long deployWei = Long.parseLong(response.body().getPriceOfCreation());
                            final long gasLimit = Long.parseLong(viewModel.getGasLimit());
                            final long gasPrice = Long.parseLong(viewModel.getGasPrice());
                            final String deployEth = CryptoFormatUtils.weiToEthLabel(String.valueOf(deployWei + (gasLimit * gasPrice)));

                            textAction.setText(String.format("%s %s", getString(R.string.start_for), deployEth));
                            textAction.setTag(String.valueOf(deployWei));
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<Estimation> call, @NonNull Throwable t) {
                        t.printStackTrace();
                    }
                });
                imageAction.setVisibility(View.GONE);
            }
        } else {
            textStatus.setText(R.string.wating_members);
            textStatus.setTextColor(textColorWaiting);
            textStatus.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pending_small_clock, 0, 0, 0);
            toolbar.setBackgroundColor(colorBlue);
            header.setBackground(ContextCompat.getDrawable(this, R.drawable.background_gradient_blue));
            imageAction.setVisibility(View.VISIBLE);
        }
    }

    /**
     * only for owners
     */
    public void leaveWallet() {
        viewModel.leaveWallet(ack -> {
            Timber.v("LEAVE: " + ack[0].toString());
            finish();
        });
    }

    /**
     * if creator completely deletes wallet
     * else removes user from owners
     */
    public void removeWallet() {
        if (viewModel.isCreator()) {
            deleteWallet();
        } else {
            leaveWallet();
        }
    }

    /**
     * only for creator, completely delete wallet
     */
    private void deleteWallet() {
        viewModel.deleteWallet(ack -> {
            Timber.v("DELETE: " + ack[0].toString());
            finish();
        });
    }

    private void onProgress(boolean isLoading) {
        if (isLoading) {
            showProgressDialog();
        } else {
            dismissProgressDialog();
        }
    }

    private void getServerConfig() {
        MultyApi.INSTANCE.getServerConfig().enqueue(new Callback<ServerConfigResponse>() {
            @Override
            public void onResponse(@NonNull Call<ServerConfigResponse> call, @NonNull Response<ServerConfigResponse> response) {
                ServerConfigResponse body = response.body();
                if (body != null && body.getMultisigFactory() != null) {
                    RealmManager.getSettingsDao().saveMultisigFactory(body.getMultisigFactory());
                }
            }

            @Override
            public void onFailure(@NonNull Call<ServerConfigResponse> call, @NonNull Throwable t) {
                t.printStackTrace();
            }
        });
    }

    public void showProgressDialog() {
        if (progressDialog == null) {
            progressDialog = new Dialog(this);
            progressDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            progressDialog.setContentView(R.layout.dialog_spinner);
            progressDialog.getWindow().setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            progressDialog.setCancelable(false);
            progressDialog.setCanceledOnTouchOutside(false);
            progressDialog.show();
        } else {
            progressDialog.show();
        }
    }

    public void dismissProgressDialog() {
        if (progressDialog != null) {
            progressDialog.dismiss();
        }
    }

    @OnClick(R.id.button_action)
    public void onClickAction(View view) {
        if (imageAction.getVisibility() == View.VISIBLE) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.root, ShareMultisigFragment.newInstance(viewModel.getInviteCode().getValue()))
                    .addToBackStack("")
                    .commit();

        } else {
            Wallet linkedWallet = viewModel.getLinkedWallet().getValue();
            MultisigFactory multisigFactory = RealmManager.getSettingsDao().getMultisigFactory();
            if (multisigFactory == null || TextUtils.isEmpty(multisigFactory.getEthMainNet()) ||
                    TextUtils.isEmpty(multisigFactory.getEthTestNet())) {
                showError(new NullPointerException("Multisig factory address is empty!"));
                getServerConfig();
                return;
            }
            view.setEnabled(false);
            final String priceOfCreation = (String) textAction.getTag();
            final byte[] seed = RealmManager.getSettingsDao().getSeed().getSeed();
            String factoryAddress = null;
            Wallet multisigWallet = viewModel.getMultisigWallet().getValue();
            if (linkedWallet.getCurrencyId() == NativeDataHelper.Blockchain.ETH.getValue() &&
                    linkedWallet.getNetworkId() == NativeDataHelper.NetworkId.RINKEBY.getValue()) {
                factoryAddress = RealmManager.getSettingsDao().getMultisigFactory().getEthTestNet();
            } else if (linkedWallet.getCurrencyId() == NativeDataHelper.Blockchain.ETH.getValue() &&
                    linkedWallet.getNetworkId() == NativeDataHelper.NetworkId.ETH_MAIN_NET.getValue()) {
                factoryAddress = RealmManager.getSettingsDao().getMultisigFactory().getEthMainNet();
            }
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("[");
            for (Owner owner : multisigWallet.getMultisigWallet().getOwners()) {
                stringBuilder.append(owner.getAddress());
                stringBuilder.append(", ");
            }
            stringBuilder.delete(stringBuilder.length() - 2, stringBuilder.length());
            stringBuilder.append("]");

            try {
                final byte[] transaction;
                if (linkedWallet.shouldUseExternalKey()) {
                    WalletPrivateKey keyObject = RealmManager.getAssetsDao().getPrivateKey(linkedWallet.getActiveAddress().getAddress(),
                            linkedWallet.getCurrencyId(), linkedWallet.getNetworkId());
                    transaction = NativeDataHelper.createEthMultisigWalletFromKey(
                            keyObject.getPrivateKey(),
                            linkedWallet.getCurrencyId(),
                            linkedWallet.getNetworkId(),
                            linkedWallet.getActiveAddress().getAmountString(),
                            viewModel.getGasLimit(),
                            viewModel.getGasPrice(),
                            linkedWallet.getEthWallet().getNonce(),
                            factoryAddress,
                            stringBuilder.toString(),
                            multisigWallet.getMultisigWallet().getConfirmations(),
                            priceOfCreation
                            );
                } else {
                   transaction = NativeDataHelper.createEthMultisigWallet(
                            seed,
                            linkedWallet.getIndex(),
                            linkedWallet.getActiveAddress().getIndex(),
                            linkedWallet.getCurrencyId(),
                            linkedWallet.getNetworkId(),
                            linkedWallet.getActiveAddress().getAmountString(),
                            viewModel.getGasLimit(),
                            viewModel.getGasPrice(),
                            linkedWallet.getEthWallet().getNonce(),
                            factoryAddress,
                            stringBuilder.toString(),
                            multisigWallet.getMultisigWallet().getConfirmations(),
                            priceOfCreation);
                }
                String hex = "0x" + byteArrayToHex(transaction);

                final HdTransactionRequestEntity entity = new HdTransactionRequestEntity(multisigWallet.getCurrencyId(), multisigWallet.getNetworkId(),
                        new HdTransactionRequestEntity.Payload("", multisigWallet.getAddresses().size(),
                                multisigWallet.getIndex(), hex, false));

                Timber.i("hex=%s", hex);
                showProgressDialog();
                MultyApi.INSTANCE.sendHdTransaction(entity).enqueue(new Callback<ResponseBody>() {
                    @Override
                    public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> response) {
                        dismissProgressDialog();
                        if (response.isSuccessful()) {
                            CompleteDialogFragment.newInstance(multisigWallet.getCurrencyId()).show(getSupportFragmentManager(), "");
                        } else {
                            try {
                                String errorBody = response.errorBody() == null ? "" : response.errorBody().string();
                                showError(new IllegalStateException(errorBody));
                                if (response.code() == 406) {
                                    Analytics.getInstance(Multy.getContext()).logEvent(getClass().getSimpleName(), "406", errorBody);
                                }
                            } catch (IOException e) {
                                showError(new IllegalStateException(""));
                            }
                            view.setEnabled(true);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                        dismissProgressDialog();
                        t.printStackTrace();
                        onError(getString(R.string.something_went_wrong));
                        view.setEnabled(true);
                    }
                });
            } catch (Exception e) {
                showError(e);
                view.setEnabled(true);
            }
        }
    }

    @OnClick(R.id.button_settings)
    public void onClickSettings() {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.root, MultisigSettingsFragment
                        .newInstance(viewModel.getMultisigWallet().getValue(), viewModel.getLinkedWallet().getValue()))
                .commit();
    }

    @OnClick(R.id.button_back)
    void onClickBack(View view) {
        view.setEnabled(false);
        onBackPressed();
    }
}
