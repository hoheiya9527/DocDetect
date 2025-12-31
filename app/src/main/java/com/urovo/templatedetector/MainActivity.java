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
import com.urovo.templatedetector.ui.TemplateListActivity;
import com.urovo.templatedetector.ui.TemplateTestActivity;

/**
 * 主界面Activity
 * 提供模板管理功能入口，并在启动时初始化所有组件
 */
public class MainActivity extends AppCompatActivity {

    private MaterialButton btnTemplateManagement;
    private MaterialButton btnContentRecognition;
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
        btnTemplateManagement = findViewById(R.id.btnTemplateManagement);
        btnContentRecognition = findViewById(R.id.btnContentRecognition);
        initContainer = findViewById(R.id.initContainer);
        initProgress = findViewById(R.id.initProgress);
        initStatusText = findViewById(R.id.initStatusText);

        btnTemplateManagement.setOnClickListener(v -> startTemplateManagement());
        btnContentRecognition.setOnClickListener(v -> startContentRecognition());
        btnTemplateManagement.setEnabled(false);
        btnContentRecognition.setEnabled(false);
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
        btnTemplateManagement.setEnabled(true);
        btnContentRecognition.setEnabled(true);
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
     * 启动模板管理Activity
     */
    private void startTemplateManagement() {
        if (!AppInitializer.getInstance(this).isInitialized()) {
            Toast.makeText(this, R.string.initializing, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, TemplateListActivity.class);
        startActivity(intent);
    }

    /**
     * 启动内容识别Activity（多模板匹配模式）
     */
    private void startContentRecognition() {
        if (!AppInitializer.getInstance(this).isInitialized()) {
            Toast.makeText(this, R.string.initializing, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, TemplateTestActivity.class);
        // 不传 templateId，进入多模板匹配模式
        startActivity(intent);
    }
}
