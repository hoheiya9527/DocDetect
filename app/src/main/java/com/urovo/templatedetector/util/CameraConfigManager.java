package com.urovo.templatedetector.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.Size;

import com.urovo.templatedetector.model.CameraSettings;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 相机配置管理器
 * 负责相机设置的持久化存储和加载
 */
public class CameraConfigManager {

    private static final String TAG = "CameraConfigManager";
    private static final String PREFS_NAME = "camera_settings";
    private static final String KEY_CAMERA_CONFIG = "camera_config";
    private static final int CONFIG_VERSION = 2;

    private final SharedPreferences preferences;
    private static CameraConfigManager instance;

    private CameraConfigManager(Context context) {
        this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 获取单例实例
     */
    public static synchronized CameraConfigManager getInstance(Context context) {
        if (instance == null) {
            instance = new CameraConfigManager(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * 保存相机设置
     */
    public boolean saveSettings(CameraSettings settings) {
        if (settings == null) {
            Log.w(TAG, "Cannot save null settings");
            return false;
        }

        try {
            JSONObject json = settingsToJson(settings);
            json.put("version", CONFIG_VERSION);
            
            boolean success = preferences.edit()
                    .putString(KEY_CAMERA_CONFIG, json.toString())
                    .commit();
            
            if (success) {
                Log.d(TAG, "Camera settings saved successfully");
            } else {
                Log.e(TAG, "Failed to save camera settings");
            }
            
            return success;
            
        } catch (JSONException e) {
            Log.e(TAG, "Failed to serialize camera settings", e);
            return false;
        }
    }

    /**
     * 加载相机设置
     */
    public CameraSettings loadSettings() {
        String configJson = preferences.getString(KEY_CAMERA_CONFIG, null);
        
        if (configJson == null) {
            Log.d(TAG, "No saved settings found, using defaults");
            return CameraSettings.createDefault();
        }

        try {
            JSONObject json = new JSONObject(configJson);
            int version = json.optInt("version", 0);
            
            if (version > CONFIG_VERSION) {
                Log.w(TAG, "Config version " + version + " is newer than supported " + CONFIG_VERSION + ", using defaults");
                return CameraSettings.createDefault();
            }
            
            CameraSettings settings = jsonToSettings(json);
            Log.d(TAG, "Camera settings loaded successfully");
            return settings;
            
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse camera settings, using defaults", e);
            return CameraSettings.createDefault();
        }
    }

    /**
     * 重置为默认设置
     */
    public boolean resetToDefaults() {
        CameraSettings defaults = CameraSettings.createDefault();
        return saveSettings(defaults);
    }

    /**
     * 检查是否有保存的设置
     */
    public boolean hasSettings() {
        return preferences.contains(KEY_CAMERA_CONFIG);
    }

    /**
     * 将设置转换为JSON
     */
    private JSONObject settingsToJson(CameraSettings settings) throws JSONException {
        JSONObject json = new JSONObject();
        
        // 基础设置
        json.put("resolution_width", settings.getResolution().getWidth());
        json.put("resolution_height", settings.getResolution().getHeight());
        json.put("focus_mode", settings.getFocusMode().name());
        json.put("exposure_compensation", settings.getExposureCompensation());
        json.put("iso", settings.getIso());
        json.put("zoom_ratio", settings.getZoomRatio());
        
        // 增强配置
        CameraSettings.EnhanceConfig enhance = settings.getEnhanceConfig();
        JSONObject enhanceJson = new JSONObject();
        enhanceJson.put("enable_detection_enhance", enhance.isEnableDetectionEnhance());
        enhanceJson.put("enable_recognition_enhance", enhance.isEnableRecognitionEnhance());
        enhanceJson.put("light_enhance_strength", enhance.getLightEnhanceStrength());
        enhanceJson.put("clahe_clip_limit", enhance.getClaheClipLimit());
        enhanceJson.put("clahe_tile_size", enhance.getClaheTileSize());
        enhanceJson.put("sharpen_strength", enhance.getSharpenStrength());
        enhanceJson.put("sharpness_threshold", enhance.getSharpnessThreshold());
        enhanceJson.put("contrast_threshold", enhance.getContrastThreshold());
        
        json.put("enhance_config", enhanceJson);
        
        // 检测设置
        json.put("confidence_threshold", settings.getConfidenceThreshold());
        json.put("auto_capture", settings.isAutoCapture());
        json.put("auto_capture_threshold", settings.getAutoCaptureThreshold());
        
        return json;
    }

    /**
     * 从JSON转换为设置
     */
    private CameraSettings jsonToSettings(JSONObject json) throws JSONException {
        CameraSettings settings = new CameraSettings();
        
        // 基础设置
        int width = json.optInt("resolution_width", 1920);
        int height = json.optInt("resolution_height", 1080);
        settings.setResolution(new Size(width, height));
        
        String focusModeStr = json.optString("focus_mode", "CONTINUOUS");
        try {
            settings.setFocusMode(CameraSettings.FocusMode.valueOf(focusModeStr));
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid focus mode: " + focusModeStr + ", using default");
            settings.setFocusMode(CameraSettings.FocusMode.CONTINUOUS);
        }
        
        settings.setExposureCompensation(json.optInt("exposure_compensation", 0));
        settings.setIso(json.optInt("iso", 0));
        settings.setZoomRatio((float) json.optDouble("zoom_ratio", 1.0));
        
        // 增强配置
        JSONObject enhanceJson = json.optJSONObject("enhance_config");
        if (enhanceJson != null) {
            CameraSettings.EnhanceConfig enhance = new CameraSettings.EnhanceConfig();
            enhance.setEnableDetectionEnhance(enhanceJson.optBoolean("enable_detection_enhance", false));
            enhance.setEnableRecognitionEnhance(enhanceJson.optBoolean("enable_recognition_enhance", true));
            enhance.setLightEnhanceStrength((float) enhanceJson.optDouble("light_enhance_strength", 0.2));
            enhance.setClaheClipLimit((float) enhanceJson.optDouble("clahe_clip_limit", 2.0));
            enhance.setClaheTileSize(enhanceJson.optInt("clahe_tile_size", 16));
            enhance.setSharpenStrength((float) enhanceJson.optDouble("sharpen_strength", 0.2));
            enhance.setSharpnessThreshold(enhanceJson.optDouble("sharpness_threshold", 100.0));
            enhance.setContrastThreshold(enhanceJson.optDouble("contrast_threshold", 0.3));
            settings.setEnhanceConfig(enhance);
        }
        
        // 检测设置
        settings.setConfidenceThreshold(json.optDouble("confidence_threshold", 0.99));
        settings.setAutoCapture(json.optBoolean("auto_capture", false));
        settings.setAutoCaptureThreshold(json.optDouble("auto_capture_threshold", 0.998));
        
        return settings;
    }
}