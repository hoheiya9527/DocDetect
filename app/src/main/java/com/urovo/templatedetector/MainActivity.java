package com.urovo.templatedetector;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.urovo.templatedetector.init.AppInitializer;

/**
 * 主界面Activity
 * 提供面单扫描功能入口，并在启动时初始化所有组件
 */
public class MainActivity extends AppCompatActivity {

    private MaterialButton btnLabelScan;
    private LinearLayout initContainer;
    private ProgressBar initProgress;
    private TextView initStatusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        startInitialization();
    }

    private void initViews() {
        btnLabelScan = findViewById(R.id.btnLabelScan);
        initContainer = findViewById(R.id.initContainer);
        initProgress = findViewById(R.id.initProgress);
        initStatusText = findViewById(R.id.initStatusText);

        btnLabelScan.setOnClickListener(v -> startLabelScan());
        btnLabelScan.setEnabled(false);
    }

    /**
     * 开始初始化所有组件
     */
    private void startInitialization() {
        AppInitializer initializer = AppInitializer.getInstance(this);
        
        if (initializer.isInitialized()) {
            onInitComplete();
            return;
        }

        initContainer.setVisibility(View.VISIBLE);
        
        initializer.initialize(new AppInitializer.InitCallback() {
            @Override
            public void onProgress(int progress, String message) {
                initProgress.setProgress(progress);
                initStatusText.setText(message);
            }

            @Override
            public void onComplete(boolean success, String errorMessage) {
                if (success) {
                    onInitComplete();
                } else {
                    onInitFailed(errorMessage);
                }
            }
        });
    }

    /**
     * 初始化完成
     */
    private void onInitComplete() {
        initContainer.setVisibility(View.GONE);
        btnLabelScan.setEnabled(true);
    }

    /**
     * 初始化失败
     */
    private void onInitFailed(String errorMessage) {
        initStatusText.setText(errorMessage);
        initProgress.setProgress(0);
        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
    }

    /**
     * 启动面单扫描Activity
     */
    private void startLabelScan() {
        if (!AppInitializer.getInstance(this).isInitialized()) {
            Toast.makeText(this, R.string.initializing, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, LabelScanActivity.class);
        startActivity(intent);
    }
}