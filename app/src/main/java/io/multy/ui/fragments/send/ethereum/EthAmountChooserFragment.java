/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.ui.fragments.send.ethereum;

import android.annotation.SuppressLint;
import android.arch.lifecycle.ViewModelProviders;
import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.constraint.ConstraintLayout;
import android.support.constraint.Group;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.SwitchCompat;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import java.math.BigDecimal;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import io.multy.R;
import io.multy.api.socket.CurrenciesRate;
import io.multy.model.entities.wallet.CurrencyCode;
import io.multy.model.entities.wallet.EthWallet;
import io.multy.storage.RealmManager;
import io.multy.ui.activities.AssetSendActivity;
import io.multy.ui.activities.BaseActivity;
import io.multy.ui.fragments.BaseFragment;
import io.multy.util.Constants;
import io.multy.util.CryptoFormatUtils;
import io.multy.util.NumberFormatter;
import io.multy.util.analytics.Analytics;
import io.multy.util.analytics.AnalyticsConstants;
import io.multy.viewmodels.AssetSendViewModel;


public class EthAmountChooserFragment extends BaseFragment implements BaseActivity.OnLockCloseListener {

    public static final String TAG = EthAmountChooserFragment.class.getSimpleName();

    public static EthAmountChooserFragment newInstance() {
        return new EthAmountChooserFragment();
    }

    @BindView(R.id.group_send)
    Group groupSend;
    @BindView(R.id.input_balance_original)
    EditText inputOriginal;
    @BindView(R.id.input_balance_currency)
    EditText inputCurrency;
    @BindView(R.id.text_spendable)
    TextView textSpendable;
    @BindView(R.id.text_total)
    TextView textTotal;
    @BindView(R.id.text_max)
    TextView textMax;
    @BindView(R.id.container_input_original)
    ConstraintLayout containerInputOriginal;
    @BindView(R.id.container_input_currency)
    ConstraintLayout containerInputCurrency;
    @BindView(R.id.switcher)
    SwitchCompat switcher;
    @BindView(R.id.button_clear_original)
    View buttonClearOriginal;
    @BindView(R.id.button_clear_currency)
    View buttonClearCurrency;

    private AssetSendViewModel viewModel;
    private CurrenciesRate currenciesRate;
    private boolean isAmountSwapped = false;
    private BigDecimal spendableWei;
    private double transactionPriceEth;

    private TextWatcher textWatcherOriginal;
    private TextWatcher textWatcherFiat;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActivity() instanceof BaseActivity) {
            ((BaseActivity) getActivity()).setOnLockCLoseListener(this);
        }
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        getActivity().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        View view = inflater.inflate(R.layout.fragment_amount_chooser_eth, container, false);
        ButterKnife.bind(this, view);
        viewModel = ViewModelProviders.of(getActivity()).get(AssetSendViewModel.class);
        setBaseViewModel(viewModel);
        currenciesRate = RealmManager.getSettingsDao().getCurrenciesRate();

        if (viewModel.getWallet() == null || !viewModel.getWallet().isValid()) {
            viewModel.setWallet(RealmManager.getAssetsDao()
                    .getWalletById(getActivity().getIntent().getExtras().getLong(Constants.EXTRA_WALLET_ID, -1)));
        }

        initWatchers();
        subscribeToUpdates();
        setupSwitcher();
        initSpendable();
        initTransactionPrice();
        setupInputEth();
        setupInputFiat();

        if (!viewModel.isAmountScanned()) {
            Analytics.getInstance(getActivity()).logSendChooseAmountLaunch(viewModel.getChainId());
        } else {
            inputOriginal.setText(viewModel.getAmount() > 0 ? String.valueOf(viewModel.getAmount()) : "");
        }
        return view;
    }

    private void initTransactionPrice() {
        if (viewModel.getWallet().getMultisigWallet() != null) {
            if (viewModel.estimation.getValue() == null) {
                viewModel.requestMultisigEstimates(viewModel.getWallet().getActiveAddress().getAddress());
            } else {
                transactionPriceEth = EthWallet.getTransactionMultisigPrice(viewModel.getFee().getAmount(),
                        Long.parseLong(viewModel.estimation.getValue().getSubmitTransaction()));
            }
        } else {
            transactionPriceEth = EthWallet.getTransactionPrice(viewModel.getFee().getAmount());
            viewModel.gasLimit.observe(this, customGasLimit -> {
                if (!TextUtils.isEmpty(customGasLimit)) {
                    transactionPriceEth = EthWallet.getTransactionPrice(viewModel.getFee().getAmount(), Long.parseLong(customGasLimit));
                }
            });
        }

    }

    @Override
    public void onResume() {
        super.onResume();
        if (!(getActivity() instanceof BaseActivity && ((BaseActivity) getActivity()).isLockVisible())) {
            requestFocusForInput();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        inputOriginal.postDelayed(() -> {
            if (getActivity() != null) {
                InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null && imm.isActive())
                    imm.hideSoftInputFromWindow(inputOriginal.getWindowToken(), 0);
            }
        }, 110);
//        inputOriginal.clearFocus();
//        inputCurrency.clearFocus();
    }

    @Override
    public void onDestroy() {
        if (getActivity() instanceof BaseActivity) {
            ((BaseActivity) getActivity()).setOnLockCLoseListener(null);
        }
        super.onDestroy();
    }

    @Override
    public void onLockClosed() {
        requestFocusForInput();
    }

    private double initSpendable() {
        return initSpendable(false);
    }

    private double initSpendable(boolean isAmountSwapped) {
        String spendableWeiString = viewModel.getWallet().getActiveAddress().getAmountString();
        spendableWei = new BigDecimal(spendableWeiString);
        final double balanceEth = CryptoFormatUtils.weiToEth(spendableWeiString);
        double spendableEth = /*switcher.isChecked() ? (balanceEth - transactionPriceEth) : */balanceEth;

        textSpendable.setText(String.format(getString(R.string.available_amount), isAmountSwapped ? CryptoFormatUtils.ethToUsd(spendableEth) + " " + CurrencyCode.USD :
        (CryptoFormatUtils.FORMAT_ETH.format(spendableEth) + " " + CurrencyCode.ETH)));
        return spendableEth;
    }

    @OnClick(R.id.button_next)
    void onClickNext() {
        if (!TextUtils.isEmpty(inputOriginal.getText()) && isParsable(inputOriginal.getText().toString()) && Double.valueOf(inputOriginal.getText().toString()) != 0) {
//            boolean invalid;
//            long inputSatoshi = CryptoFormatUtils.btcToSatoshi(inputOriginal.getText().toString());
//            if (switcher.isChecked()) {
//                invalid = getFeePlusDonation() + inputSatoshi > spendableSatoshi;
//            } else {
//                if (inputSatoshi == spendableSatoshi) {
//                    invalid = false;
//                } else {
//                    invalid = inputSatoshi - getFeePlusDonation() >= spendableSatoshi;
//                }
//            }
//
//            if (invalid) {
//                Toast.makeText(getActivity(), R.string.error_balance, Toast.LENGTH_LONG).show();
//            } else if (!invalid) {
            viewModel.setAmount(Double.valueOf(inputOriginal.getText().toString()));
            viewModel.signTransactionEth();
//            }
//        } else {
//            Toast.makeText(getActivity(), R.string.choose_amount, Toast.LENGTH_SHORT).show();
        }
    }

    @OnClick(R.id.image_swap)
    void onClickImageSwap() {
        if (isAmountSwapped) {
            inputOriginal.requestFocus();
        } else {
            inputCurrency.requestFocus();
        }
        Analytics.getInstance(getActivity()).logSendChooseAmount(AnalyticsConstants.SEND_AMOUNT_SWAP, viewModel.getChainId());
    }

    private void checkCommas() {
        String input = inputCurrency.getText().toString();
        if (!input.equals("") && input.contains(",")) {
            inputCurrency.setText(input.replaceAll(",", "."));
        }

        input = inputOriginal.getText().toString();
        if (!input.equals("") && input.contains(",")) {
            inputOriginal.setText(input.replaceAll(",", "."));
        }
    }

    private void requestFocusForInput() {
        inputOriginal.requestFocus();
        inputOriginal.postDelayed(() -> showKeyboard(getActivity(), inputOriginal), 300);
    }

    @OnClick(R.id.text_max)
    void onClickMax() {
//        if (textMax.isSelected()) {
//            textMax.setSelected(false);
//        } else {
//            textMax.setSelected(true);
//        }
        switcher.setChecked(false);

        disableInputsListeners();
        final String maxSpendableEth = getMaxSpendableEth();

        inputOriginal.setText(maxSpendableEth);
        inputOriginal.setSelection(maxSpendableEth.length());
        inputCurrency.setText(CryptoFormatUtils.ethToUsd(Double.parseDouble(maxSpendableEth)));
        setMaxAmountToSpend();

        enableInputsListeners();
    }

    private String getMaxSpendableEth() {
        String maxSpendableEth = CryptoFormatUtils.FORMAT_ETH.format(initSpendable());
        if (maxSpendableEth.contains(",")) {
            maxSpendableEth = maxSpendableEth.replaceAll(",", ".");
        }

        return maxSpendableEth;
    }

    private void animateOriginalBalance() {
        containerInputOriginal.animate().scaleY(1.5f).setInterpolator(new AccelerateInterpolator()).setDuration(300);
        containerInputOriginal.animate().scaleX(1.5f).setInterpolator(new AccelerateInterpolator()).setDuration(300);
        containerInputCurrency.animate().scaleY(1f).setInterpolator(new AccelerateInterpolator()).setDuration(300);
        containerInputCurrency.animate().scaleX(1f).setInterpolator(new AccelerateInterpolator()).setDuration(300);
        inputOriginal.setTextColor(ContextCompat.getColor(getActivity(), R.color.text_main));
        inputCurrency.setTextColor(ContextCompat.getColor(getActivity(), R.color.text_grey));
        isAmountSwapped = false;
    }

    private void animateCurrencyBalance() {
        containerInputOriginal.animate().scaleY(1f).setInterpolator(new AccelerateDecelerateInterpolator()).setDuration(300);
        containerInputOriginal.animate().scaleX(1f).setInterpolator(new AccelerateDecelerateInterpolator()).setDuration(300);
        containerInputCurrency.animate().scaleY(1.5f).setInterpolator(new AccelerateDecelerateInterpolator()).setDuration(300);
        containerInputCurrency.animate().scaleX(1.5f).setInterpolator(new AccelerateDecelerateInterpolator()).setDuration(300);
        inputOriginal.setTextColor(ContextCompat.getColor(getActivity(), R.color.text_grey));
        inputCurrency.setTextColor(ContextCompat.getColor(getActivity(), R.color.text_main));
        isAmountSwapped = true;
    }

    private boolean showTotalSum(String sum) {
        if (sum.equals("") || sum.equals(".")) {
            sum = "0";
        } else {
            if (!isAmountSwapped) {
                //CASE FOR ETH SELECTED
                if (switcher.isChecked()) {
                    double totalSum = Double.parseDouble(sum);
                    totalSum += transactionPriceEth;
                    sum = CryptoFormatUtils.FORMAT_ETH.format(totalSum);
                }
                if (Double.parseDouble(sum) > CryptoFormatUtils.weiToEth(viewModel.getWallet().getAvailableBalance())) {
                    return false;
                }
            } else {
                //CASE FOR FIAT SELECTED
                if (switcher.isChecked()) {
                    sum = String.valueOf(Double.parseDouble(sum) + Double.parseDouble(CryptoFormatUtils.ethToUsd(transactionPriceEth)));
                }
                if (new BigDecimal(sum).compareTo(new BigDecimal(CryptoFormatUtils.ethToUsd(CryptoFormatUtils.weiToEth(viewModel.getWallet().getAvailableBalance())))) > 0) {
                    return false;
                }
            }
        }

        textTotal.setText(sum);
        textTotal.append(Constants.SPACE);
        textTotal.append(isAmountSwapped ? CurrencyCode.USD.name() : CurrencyCode.ETH.name());
        viewModel.setAmount(Double.parseDouble(sum));
        return true;
    }

    private void initWatchers() {
        textWatcherOriginal = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                if (!isAmountSwapped) {
                    if (!TextUtils.isEmpty(charSequence)) {
                        if (isParsable(charSequence.toString())) {
                            final double ethValue = Double.parseDouble(charSequence.toString());
                            final String fiatValue = CryptoFormatUtils.ethToUsd(ethValue);
                            inputCurrency.setText(fiatValue);
                            if (!showTotalSum(charSequence.toString())) {
                                viewModel.errorMessage.setValue(getString(R.string.enter_sum_too_much));
                                int subCount = inputOriginal.getText().length() - 1;
                                inputOriginal.setText(inputOriginal.getText().subSequence(0, subCount < 0 ? 0 : subCount));
                                inputOriginal.setSelection(inputOriginal.getText().length());
                            }
                        }
                    } else {
                        showTotalSum("");
                        inputCurrency.getText().clear();
                        inputOriginal.getText().clear();
                    }
                    checkMaxLengthAfterPoint(inputOriginal, 10, i, i2);
                    checkMaxLengthBeforePoint(inputOriginal, 6, i, i1, i2);
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (!isAmountSwapped) {
                    checkForPointAndZeros(editable.toString(), inputOriginal);
                }
            }
        };

        textWatcherFiat = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                if (isAmountSwapped) {
                    if (!TextUtils.isEmpty(charSequence)) {
                        if (isParsable(charSequence.toString())) {
                            final double fiatValue = Double.parseDouble(charSequence.toString());
                            final String ethValue = CryptoFormatUtils.usdToEth(fiatValue);
                            inputOriginal.setText(ethValue);
//                            showTotalSum(String.valueOf(fiatValue));
                        }
                    } else {
                        showTotalSum("");
                        inputCurrency.getText().clear();
                        inputOriginal.getText().clear();
                    }

                    checkMaxLengthAfterPoint(inputCurrency, 3, i, i2);
                    if (isAmountSwapped) {
                        checkMaxLengthBeforePoint(inputCurrency, 9, i, i1, i2);
                    } else {
                        checkMaxLengthBeforePoint(inputCurrency, 10, i, i1, i2);
                    }
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (isAmountSwapped) {
                    if (showTotalSum(editable.toString())) {
                        checkForPointAndZeros(editable.toString(), inputCurrency);
                    } else {
                        viewModel.errorMessage.setValue(getString(R.string.enter_sum_too_much));
                        int subCount = inputCurrency.getText().length() - 1;
                        inputCurrency.setText(inputCurrency.getText().subSequence(0, subCount < 0 ? 0 : subCount));
                        inputCurrency.setSelection(inputCurrency.getText().length());
                    }
                }
            }
        };
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupInputEth() {
        if (viewModel.getAmount() != 0) {
            inputOriginal.setText(NumberFormatter.getInstance().format(viewModel.getAmount()));
        }

        inputOriginal.setOnTouchListener((v, event) -> {
            inputOriginal.setSelection(inputOriginal.getText().length());
            if (!inputOriginal.hasFocus()) {
                inputOriginal.requestFocus();
                return true;
            }
            showKeyboard(getActivity(), v);
            Analytics.getInstance(getActivity()).logSendChooseAmount(AnalyticsConstants.SEND_AMOUNT_CRYPTO, viewModel.getChainId());
            return true;
        });

        inputOriginal.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                EthAmountChooserFragment.this.animateOriginalBalance();
                inputOriginal.setSelection(inputOriginal.getText().length());
                showTotalSum(inputOriginal.getText().toString());
                buttonClearOriginal.setVisibility(View.VISIBLE);
                buttonClearCurrency.setVisibility(View.GONE);
            }
            initSpendable(!hasFocus);
        });

        inputOriginal.addTextChangedListener(textWatcherOriginal);
    }

    private void setupInputFiat() {
        if (viewModel.getAmount() != 0) {
            inputCurrency.setText(NumberFormatter.getFiatInstance().format(viewModel.getAmount() * currenciesRate.getEthToUsd()));
        }

        inputCurrency.setOnTouchListener((v, event) -> {
            inputCurrency.setSelection(inputCurrency.getText().length());
            if (!inputCurrency.hasFocus()) {
                inputCurrency.requestFocus();
                return true;
            }
            showKeyboard(getActivity(), v);
            Analytics.getInstance(getActivity()).logSendChooseAmount(AnalyticsConstants.SEND_AMOUNT_FIAT, viewModel.getChainId());
            return true;
        });

        inputCurrency.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                animateCurrencyBalance();
                inputCurrency.setSelection(inputCurrency.getText().length());
                showTotalSum(inputCurrency.getText().toString());
                buttonClearOriginal.setVisibility(View.GONE);
                buttonClearCurrency.setVisibility(View.VISIBLE);
            }
        });

        inputCurrency.addTextChangedListener(textWatcherFiat);
    }

    private void disableInputsListeners() {
        inputOriginal.removeTextChangedListener(textWatcherOriginal);
        inputCurrency.removeTextChangedListener(textWatcherFiat);
    }

    private void enableInputsListeners() {
        inputOriginal.addTextChangedListener(textWatcherOriginal);
        inputCurrency.addTextChangedListener(textWatcherFiat);
    }

    /**
     * Sets total spending amount from wallet.
     * Checks if amount are set in original currency or usd, eur, etc.
     */
    private void setMaxAmountToSpend() {
        if (isAmountSwapped) {
            textTotal.setText(viewModel.getWallet().getFiatBalanceLabelTrimmed());
            textTotal.append(Constants.SPACE);
            textTotal.append(CurrencyCode.USD.name());
        } else {
            textTotal.setText(viewModel.getWallet().getBalanceLabel());
        }
    }


    private boolean isParsable(String input) {
        try {
            input = input.replaceAll(",", ".");
            Double.parseDouble(input);
            return true;
        } catch (NumberFormatException e) {
            e.printStackTrace();
            return false;
        }
    }

    private void checkForPointAndZeros(String input, EditText inputView) {
        int selection = inputView.getSelectionStart();
        if (!TextUtils.isEmpty(input) && input.length() == 1 && input.contains(".")) {
            String result = input.replaceAll(".", "0.");
            inputView.setText(result);
            inputView.setSelection(result.length());
        } else if (!TextUtils.isEmpty(input) && input.startsWith("00")) {
            inputView.setText(input.substring(1, input.length()));
            inputView.setSelection(selection - 1);
        }
    }

    private void checkMaxLengthBeforePoint(EditText input, int max, int start, int end, int count) {
        String amount = input.getText().toString();
        if (!TextUtils.isEmpty(amount) && amount.length() > max) {
            if (amount.contains(".")) {
                if (amount.indexOf(".") > max) {
                    if (start != 0 && end != amount.length() && count == amount.length()) {
                        StringBuilder stringBuilder = new StringBuilder();
                        stringBuilder.append(amount.substring(0, start));
                        stringBuilder.append(amount.substring(start + count, amount.length()));
                        input.setText(stringBuilder.toString());
                        if (start <= input.getText().length()) {
                            input.setSelection(start);
                        } else {
                            input.setSelection(input.getText().length());
                        }
                    } else {
                        input.setText(amount.substring(0, amount.length() - 1));
                        input.setSelection(input.getText().length());
                    }
                }
            } else {
                if (start != 0 && end != amount.length() && count == amount.length()) {
                    StringBuilder stringBuilder = new StringBuilder();
                    stringBuilder.append(amount, 0, start);
                    stringBuilder.append(amount, start + count, amount.length());
                    input.setText(stringBuilder.toString());
                    input.setSelection(start);
                } else {
                    input.setText(amount.substring(0, amount.length() - 1));
                    input.setSelection(input.getText().length());
                }
            }
        }
    }

    private void checkMaxLengthAfterPoint(EditText input, int max, int start, int count) {
        String amount = input.getText().toString();
        if (!TextUtils.isEmpty(amount) && amount.contains(".")) {
            if (amount.length() - amount.indexOf(".") > max) {
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append(amount, 0, start);
                stringBuilder.append(amount, start + count, amount.length());
                input.setText(stringBuilder.toString());
                input.setSelection(start);
            }
        }
    }

    private void subscribeToUpdates() {
        viewModel.transaction.observe(this, s -> ((AssetSendActivity) getActivity()).setFragment(R.string.send_summary, R.id.container, EthSendSummaryFragment.newInstance()));
    }

    private void setupSwitcher() {
        if (viewModel.getWallet().isMultisig()) {
            switcher.setEnabled(false);
            switcher.setChecked(true);
        } else {
            switcher.setOnCheckedChangeListener((compoundButton, isChecked) -> {
                viewModel.setPayForCommission(isChecked);
                checkCommas();
                initSpendable();
                if (showTotalSum(isAmountSwapped ? inputCurrency.getText().toString() : inputOriginal.getText().toString())) {
                    if (isChecked) {
                        Analytics.getInstance(getActivity()).logSendChooseAmount(AnalyticsConstants.SEND_AMOUNT_COMMISSION_ENABLED, viewModel.getChainId());
                    } else {
                        Analytics.getInstance(getActivity()).logSendChooseAmount(AnalyticsConstants.SEND_AMOUNT_COMMISSION_DISABLED, viewModel.getChainId());
                    }
                } else if (isChecked) {
                    switcher.setChecked(false);
                    viewModel.errorMessage.setValue(getString(R.string.enter_sum_too_much));
                }
            });
            switcher.setChecked(viewModel.isPayForCommission());
        }
    }

    @OnClick(R.id.button_clear_original)
    void onClickClearOriginal() {
        inputOriginal.setText("");
        switcher.setChecked(true);
    }

    @OnClick(R.id.button_clear_currency)
    void onClickClearCurrency() {
        inputCurrency.setText("");
        switcher.setChecked(true);
    }

    @OnClick(R.id.container_input_original)
    void onClickInputOriginal() {
        if (!inputOriginal.hasFocus()) {
            inputOriginal.requestFocus();
        }
        showKeyboard(getActivity(), inputOriginal);
    }

    @OnClick(R.id.container_input_currency)
    void onClickInputCurrency() {
        if (!inputCurrency.hasFocus()) {
            inputCurrency.requestFocus();
        }
        showKeyboard(getActivity(), inputCurrency);
    }
}