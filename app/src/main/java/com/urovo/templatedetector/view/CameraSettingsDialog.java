package com.urovo.templatedetector.view;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.urovo.templatedetector.R;
import com.urovo.templatedetector.model.CameraSettings;

/**
 * 相机设置对话框
 */
public class CameraSettingsDialog extends DialogFragment {

    public interface OnSettingsChangedListener {
        void onSettingsChanged(CameraSettings settings);
    }

    private CameraSettings currentSettings;
    private OnSettingsChangedListener listener;

    // 基础设置控件
    private Spinner spinnerResolution;
    private Spinner spinnerFocusMode;
    private SeekBar seekBarExposure;
    private TextView exposureValue;
    private CheckBox checkBoxAutoExposure;
    private LinearLayout layoutShutterSpeed;
    private Spinner spinnerShutterSpeed;
    private LinearLayout layoutIso;
    private Spinner spinnerIso;

    // 图像增强设置控件
    private CheckBox checkBoxEnableEnhance;

    // 检测设置控件
    private EditText editTextConfidenceThreshold;
    private CheckBox checkBoxAutoCapture;
    private EditText editTextAutoCaptureThreshold;

    private static final Size[] RESOLUTIONS = CameraSettings.getSupportedAnalysisResolutions();

    private static final String[] RESOLUTION_LABELS = {
            "720P",
            "1080P",
            "2K",
            "4K"
    };

    private static final String[] FOCUS_MODE_LABELS = {
            "自动对焦",
            "连续对焦",
            "手动对焦"
    };

    private static final int[] ISO_VALUES = {0, 100, 200, 400, 800, 1600, 3200};
    private static final String[] ISO_LABELS = {"自动", "100", "200", "400", "800", "1600", "3200"};

    public static CameraSettingsDialog newInstance(CameraSettings settings) {
        CameraSettingsDialog dialog = new CameraSettingsDialog();
        dialog.currentSettings = settings != null ? settings.copy() : CameraSettings.createDefault();
        return dialog;
    }

    public void setOnSettingsChangedListener(OnSettingsChangedListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Context context = requireContext();
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_camera_settings, null);

        initViews(view);
        populateSettings();

        return new AlertDialog.Builder(context)
                .setTitle(R.string.camera_settings)
                .setView(view)
                .setPositiveButton(R.string.apply, (dialog, which) -> {
                    applySettings();
                    if (listener != null) {
                        listener.onSettingsChanged(currentSettings);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
    }

    private void initViews(View view) {
        // 基础设置控件
        spinnerResolution = view.findViewById(R.id.spinnerResolution);
        spinnerFocusMode = view.findViewById(R.id.spinnerFocusMode);
        seekBarExposure = view.findViewById(R.id.seekBarExposure);
        exposureValue = view.findViewById(R.id.exposureValue);
        checkBoxAutoExposure = view.findViewById(R.id.checkBoxAutoExposure);
        layoutShutterSpeed = view.findViewById(R.id.layoutShutterSpeed);
        spinnerShutterSpeed = view.findViewById(R.id.spinnerShutterSpeed);
        layoutIso = view.findViewById(R.id.layoutIso);
        spinnerIso = view.findViewById(R.id.spinnerIso);

        // 图像增强设置控件
        checkBoxEnableEnhance = view.findViewById(R.id.checkBoxEnableEnhance);

        // 检测设置控件
        editTextConfidenceThreshold = view.findViewById(R.id.editTextConfidenceThreshold);
        checkBoxAutoCapture = view.findViewById(R.id.checkBoxAutoCapture);
        editTextAutoCaptureThreshold = view.findViewById(R.id.editTextAutoCaptureThreshold);

        initBasicControls();
        initEnhanceControls();
        initDetectionControls();
    }

    private void initBasicControls() {
        // 设置分辨率适配器
        ArrayAdapter<String> resolutionAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                RESOLUTION_LABELS
        );
        resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerResolution.setAdapter(resolutionAdapter);

        // 设置对焦模式适配器
        ArrayAdapter<String> focusModeAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                FOCUS_MODE_LABELS
        );
        focusModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerFocusMode.setAdapter(focusModeAdapter);

        // 设置ISO适配器
        ArrayAdapter<String> isoAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                ISO_LABELS
        );
        isoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerIso.setAdapter(isoAdapter);

        // 设置快门速度适配器
        ArrayAdapter<String> shutterSpeedAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                CameraSettings.SHUTTER_SPEED_LABELS
        );
        shutterSpeedAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerShutterSpeed.setAdapter(shutterSpeedAdapter);

        // 自动曝光开关监听 - AE开则隐藏快门和ISO，关则显示
        checkBoxAutoExposure.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateManualExposureVisibility(!isChecked);
        });

        // 曝光补偿监听
        seekBarExposure.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress - 2; // 范围 -2 到 +2
                exposureValue.setText(String.valueOf(value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void initEnhanceControls() {
        // 增强开关无需额外初始化
    }

    /**
     * 更新手动曝光控件可见性
     * @param visible true显示快门和ISO设置，false隐藏
     */
    private void updateManualExposureVisibility(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        layoutShutterSpeed.setVisibility(visibility);
        layoutIso.setVisibility(visibility);
    }

    private void initDetectionControls() {
        // 置信度阈值输入框验证
        editTextConfidenceThreshold.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                validateConfidenceInput();
            }
        });
        
        editTextConfidenceThreshold.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                validateConfidenceInput();
            }
            return false;
        });

        // 自动捕获阈值输入框验证
        editTextAutoCaptureThreshold.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                validateAutoCaptureInput();
            }
        });
        
        editTextAutoCaptureThreshold.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                validateAutoCaptureInput();
            }
            return false;
        });

        // 自动捕获开关监听
        checkBoxAutoCapture.setOnCheckedChangeListener((buttonView, isChecked) -> {
            editTextAutoCaptureThreshold.setEnabled(isChecked);
        });
    }
    
    private void validateConfidenceInput() {
        String text = editTextConfidenceThreshold.getText().toString().trim();
        if (text.isEmpty()) {
            editTextConfidenceThreshold.setText("990");
            return;
        }
        
        try {
            int value = Integer.parseInt(text);
            if (value < 100) {
                editTextConfidenceThreshold.setText("100");
            } else if (value > 1000) {
                editTextConfidenceThreshold.setText("1000");
            } else {
                // 保持原值，确保是整数
                editTextConfidenceThreshold.setText(String.valueOf(value));
            }
        } catch (NumberFormatException e) {
            editTextConfidenceThreshold.setText("990");
        }
    }

    private void validateAutoCaptureInput() {
        String text = editTextAutoCaptureThreshold.getText().toString().trim();
        if (text.isEmpty()) {
            editTextAutoCaptureThreshold.setText("998");
            return;
        }
        
        try {
            int value = Integer.parseInt(text);
            if (value < 100) {
                editTextAutoCaptureThreshold.setText("100");
            } else if (value > 1000) {
                editTextAutoCaptureThreshold.setText("1000");
            } else {
                // 保持原值，确保是整数
                editTextAutoCaptureThreshold.setText(String.valueOf(value));
            }
        } catch (NumberFormatException e) {
            editTextAutoCaptureThreshold.setText("998");
        }
    }

    private void populateSettings() {
        if (currentSettings == null) {
            currentSettings = CameraSettings.createDefault();
        }

        populateBasicSettings();
        populateEnhanceSettings();
        populateDetectionSettings();
    }

    private void populateBasicSettings() {
        // 分析分辨率
        Size resolution = currentSettings.getAnalysisResolution();
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            if (RESOLUTIONS[i].equals(resolution)) {
                spinnerResolution.setSelection(i);
                break;
            }
        }

        // 对焦模式
        spinnerFocusMode.setSelection(currentSettings.getFocusMode().ordinal());

        // 曝光补偿
        int exposure = currentSettings.getExposureCompensation();
        seekBarExposure.setProgress(exposure + 2);
        exposureValue.setText(String.valueOf(exposure));

        // 自动曝光
        boolean autoExposure = currentSettings.isAutoExposure();
        checkBoxAutoExposure.setChecked(autoExposure);
        updateManualExposureVisibility(!autoExposure);

        // 快门速度
        long shutterSpeed = currentSettings.getShutterSpeed();
        long[] shutterValues = CameraSettings.SHUTTER_SPEED_VALUES;
        for (int i = 0; i < shutterValues.length; i++) {
            if (shutterValues[i] == shutterSpeed) {
                spinnerShutterSpeed.setSelection(i);
                break;
            }
        }

        // ISO
        int iso = currentSettings.getIso();
        for (int i = 0; i < ISO_VALUES.length; i++) {
            if (ISO_VALUES[i] == iso) {
                spinnerIso.setSelection(i);
                break;
            }
        }
    }

    private void populateEnhanceSettings() {
        CameraSettings.EnhanceConfig enhance = currentSettings.getEnhanceConfig();
        checkBoxEnableEnhance.setChecked(enhance.isEnableEnhance());
    }

    private void populateDetectionSettings() {
        // 置信度阈值 - 转换为三位数显示 (0.99 -> 990)
        double confidence = currentSettings.getConfidenceThreshold();
        int confidenceInt = (int) Math.round(confidence * 1000);
        editTextConfidenceThreshold.setText(String.valueOf(confidenceInt));

        // 自动捕获开关
        checkBoxAutoCapture.setChecked(currentSettings.isAutoCapture());

        // 自动捕获阈值 - 转换为三位数显示 (0.998 -> 998)
        double autoCapture = currentSettings.getAutoCaptureThreshold();
        int autoCaptureInt = (int) Math.round(autoCapture * 1000);
        editTextAutoCaptureThreshold.setText(String.valueOf(autoCaptureInt));

        // 根据开关状态启用/禁用自动捕获阈值输入框
        editTextAutoCaptureThreshold.setEnabled(currentSettings.isAutoCapture());
    }

    private void applySettings() {
        applyBasicSettings();
        applyEnhanceSettings();
        applyDetectionSettings();
    }

    private void applyBasicSettings() {
        // 分析分辨率
        int resolutionIndex = spinnerResolution.getSelectedItemPosition();
        if (resolutionIndex >= 0 && resolutionIndex < RESOLUTIONS.length) {
            currentSettings.setAnalysisResolution(RESOLUTIONS[resolutionIndex]);
        }

        // 对焦模式
        int focusModeIndex = spinnerFocusMode.getSelectedItemPosition();
        currentSettings.setFocusMode(CameraSettings.FocusMode.values()[focusModeIndex]);

        // 曝光补偿
        currentSettings.setExposureCompensation(seekBarExposure.getProgress() - 2);

        // 自动曝光
        currentSettings.setAutoExposure(checkBoxAutoExposure.isChecked());

        // 快门速度
        int shutterIndex = spinnerShutterSpeed.getSelectedItemPosition();
        if (shutterIndex >= 0 && shutterIndex < CameraSettings.SHUTTER_SPEED_VALUES.length) {
            currentSettings.setShutterSpeed(CameraSettings.SHUTTER_SPEED_VALUES[shutterIndex]);
        }

        // ISO
        int isoIndex = spinnerIso.getSelectedItemPosition();
        if (isoIndex >= 0 && isoIndex < ISO_VALUES.length) {
            currentSettings.setIso(ISO_VALUES[isoIndex]);
        }
    }

    private void applyEnhanceSettings() {
        CameraSettings.EnhanceConfig enhance = currentSettings.getEnhanceConfig();
        enhance.setEnableEnhance(checkBoxEnableEnhance.isChecked());
    }

    private void applyDetectionSettings() {
        // 置信度阈值 - 从三位数转换为小数 (990 -> 0.99)
        String text = editTextConfidenceThreshold.getText().toString().trim();
        try {
            int confidenceInt = Integer.parseInt(text);
            // 确保在有效范围内 (100-1000)
            confidenceInt = Math.max(100, Math.min(1000, confidenceInt));
            double confidence = confidenceInt / 1000.0;
            currentSettings.setConfidenceThreshold(confidence);
        } catch (NumberFormatException e) {
            // 如果解析失败，使用默认值 990 (0.99)
            currentSettings.setConfidenceThreshold(0.99);
        }

        // 自动捕获开关
        currentSettings.setAutoCapture(checkBoxAutoCapture.isChecked());

        // 自动捕获阈值 - 从三位数转换为小数 (998 -> 0.998)
        String autoCaptureText = editTextAutoCaptureThreshold.getText().toString().trim();
        try {
            int autoCaptureInt = Integer.parseInt(autoCaptureText);
            // 确保在有效范围内 (100-1000)
            autoCaptureInt = Math.max(100, Math.min(1000, autoCaptureInt));
            double autoCaptureThreshold = autoCaptureInt / 1000.0;
            currentSettings.setAutoCaptureThreshold(autoCaptureThreshold);
        } catch (NumberFormatException e) {
            // 如果解析失败，使用默认值 998 (0.998)
            currentSettings.setAutoCaptureThreshold(0.998);
        }
    }
}
