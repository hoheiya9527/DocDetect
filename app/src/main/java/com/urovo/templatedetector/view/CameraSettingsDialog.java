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
    private Spinner spinnerIso;

    // 图像增强设置控件
    private CheckBox checkBoxDetectionEnhance;
    private CheckBox checkBoxRecognitionEnhance;
    private SeekBar seekBarLightEnhanceStrength;
    private TextView lightEnhanceStrengthValue;
    private SeekBar seekBarClaheClipLimit;
    private TextView claheClipLimitValue;
    private SeekBar seekBarClaheTileSize;
    private TextView claheTileSizeValue;
    private SeekBar seekBarSharpenStrength;
    private TextView sharpenStrengthValue;
    private SeekBar seekBarSharpnessThreshold;
    private TextView sharpnessThresholdValue;
    private SeekBar seekBarContrastThreshold;
    private TextView contrastThresholdValue;

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
        spinnerIso = view.findViewById(R.id.spinnerIso);

        // 图像增强设置控件
        checkBoxDetectionEnhance = view.findViewById(R.id.checkBoxDetectionEnhance);
        checkBoxRecognitionEnhance = view.findViewById(R.id.checkBoxRecognitionEnhance);
        seekBarLightEnhanceStrength = view.findViewById(R.id.seekBarLightEnhanceStrength);
        lightEnhanceStrengthValue = view.findViewById(R.id.lightEnhanceStrengthValue);
        seekBarClaheClipLimit = view.findViewById(R.id.seekBarClaheClipLimit);
        claheClipLimitValue = view.findViewById(R.id.claheClipLimitValue);
        seekBarClaheTileSize = view.findViewById(R.id.seekBarClaheTileSize);
        claheTileSizeValue = view.findViewById(R.id.claheTileSizeValue);
        seekBarSharpenStrength = view.findViewById(R.id.seekBarSharpenStrength);
        sharpenStrengthValue = view.findViewById(R.id.sharpenStrengthValue);
        seekBarSharpnessThreshold = view.findViewById(R.id.seekBarSharpnessThreshold);
        sharpnessThresholdValue = view.findViewById(R.id.sharpnessThresholdValue);
        seekBarContrastThreshold = view.findViewById(R.id.seekBarContrastThreshold);
        contrastThresholdValue = view.findViewById(R.id.contrastThresholdValue);

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
        // 轻量增强强度 (0.0-1.0)
        seekBarLightEnhanceStrength.setMax(100);
        seekBarLightEnhanceStrength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = progress / 100.0f;
                lightEnhanceStrengthValue.setText(String.format("%.2f", value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // CLAHE限制 (1.0-4.0)
        seekBarClaheClipLimit.setMax(300);
        seekBarClaheClipLimit.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = 1.0f + (progress / 100.0f);
                claheClipLimitValue.setText(String.format("%.1f", value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // CLAHE网格大小 (4-32)
        seekBarClaheTileSize.setMax(28);
        seekBarClaheTileSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = 4 + progress;
                claheTileSizeValue.setText(String.valueOf(value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 锐化强度 (0.0-1.0)
        seekBarSharpenStrength.setMax(100);
        seekBarSharpenStrength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = progress / 100.0f;
                sharpenStrengthValue.setText(String.format("%.2f", value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 清晰度阈值 (50-300)
        seekBarSharpnessThreshold.setMax(250);
        seekBarSharpnessThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                double value = 50.0 + progress;
                sharpnessThresholdValue.setText(String.format("%.0f", value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 对比度阈值 (0.1-1.0)
        seekBarContrastThreshold.setMax(90);
        seekBarContrastThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                double value = 0.1 + (progress / 100.0);
                contrastThresholdValue.setText(String.format("%.2f", value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
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

        // 增强开关
        checkBoxDetectionEnhance.setChecked(enhance.isEnableDetectionEnhance());
        checkBoxRecognitionEnhance.setChecked(enhance.isEnableRecognitionEnhance());

        // 轻量增强强度
        int lightStrengthProgress = (int) (enhance.getLightEnhanceStrength() * 100);
        seekBarLightEnhanceStrength.setProgress(lightStrengthProgress);
        lightEnhanceStrengthValue.setText(String.format("%.2f", enhance.getLightEnhanceStrength()));

        // CLAHE限制
        int claheProgress = (int) ((enhance.getClaheClipLimit() - 1.0f) * 100);
        seekBarClaheClipLimit.setProgress(claheProgress);
        claheClipLimitValue.setText(String.format("%.1f", enhance.getClaheClipLimit()));

        // CLAHE网格大小
        seekBarClaheTileSize.setProgress(enhance.getClaheTileSize() - 4);
        claheTileSizeValue.setText(String.valueOf(enhance.getClaheTileSize()));

        // 锐化强度
        int sharpenProgress = (int) (enhance.getSharpenStrength() * 100);
        seekBarSharpenStrength.setProgress(sharpenProgress);
        sharpenStrengthValue.setText(String.format("%.2f", enhance.getSharpenStrength()));

        // 清晰度阈值
        int sharpnessProgress = (int) (enhance.getSharpnessThreshold() - 50.0);
        seekBarSharpnessThreshold.setProgress(sharpnessProgress);
        sharpnessThresholdValue.setText(String.format("%.0f", enhance.getSharpnessThreshold()));

        // 对比度阈值
        int contrastProgress = (int) ((enhance.getContrastThreshold() - 0.1) * 100);
        seekBarContrastThreshold.setProgress(contrastProgress);
        contrastThresholdValue.setText(String.format("%.2f", enhance.getContrastThreshold()));
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

        // ISO
        int isoIndex = spinnerIso.getSelectedItemPosition();
        if (isoIndex >= 0 && isoIndex < ISO_VALUES.length) {
            currentSettings.setIso(ISO_VALUES[isoIndex]);
        }
    }

    private void applyEnhanceSettings() {
        CameraSettings.EnhanceConfig enhance = currentSettings.getEnhanceConfig();

        // 增强开关
        enhance.setEnableDetectionEnhance(checkBoxDetectionEnhance.isChecked());
        enhance.setEnableRecognitionEnhance(checkBoxRecognitionEnhance.isChecked());

        // 轻量增强强度
        enhance.setLightEnhanceStrength(seekBarLightEnhanceStrength.getProgress() / 100.0f);

        // CLAHE限制
        enhance.setClaheClipLimit(1.0f + (seekBarClaheClipLimit.getProgress() / 100.0f));

        // CLAHE网格大小
        enhance.setClaheTileSize(4 + seekBarClaheTileSize.getProgress());

        // 锐化强度
        enhance.setSharpenStrength(seekBarSharpenStrength.getProgress() / 100.0f);

        // 清晰度阈值
        enhance.setSharpnessThreshold(50.0 + seekBarSharpnessThreshold.getProgress());

        // 对比度阈值
        enhance.setContrastThreshold(0.1 + (seekBarContrastThreshold.getProgress() / 100.0));
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
