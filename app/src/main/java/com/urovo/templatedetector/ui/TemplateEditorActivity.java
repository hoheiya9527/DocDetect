package com.urovo.templatedetector.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.urovo.templatedetector.R;
import com.urovo.templatedetector.extractor.ContentExtractor;
import com.urovo.templatedetector.init.AppInitializer;
import com.urovo.templatedetector.matcher.TemplateMatchingService;
import com.urovo.templatedetector.matcher.TemplateRepository;
import com.urovo.templatedetector.model.CameraSettings;
import com.urovo.templatedetector.model.DetectedRegion;
import com.urovo.templatedetector.model.Template;
import com.urovo.templatedetector.model.TemplateRegion;
import com.urovo.templatedetector.util.CameraConfigManager;
import com.urovo.templatedetector.view.RegionDrawView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板编辑界面
 * 支持创建新模板和编辑已有模板的区域
 */
public class TemplateEditorActivity extends AppCompatActivity 
        implements RegionDrawView.OnRegionListener {

    private static final String TAG = "TemplateEditorActivity";

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_TEMPLATE_ID = "template_id";
    public static final String EXTRA_CATEGORY_ID = "category_id";
    public static final String EXTRA_IMAGE_PATH = "image_path";

    public static final int MODE_CREATE = 1;
    public static final int MODE_EDIT = 2;

    private Toolbar toolbar;
    private RegionDrawView regionDrawView;
    private RecyclerView recyclerViewRegions;
    private MaterialButton buttonAddRegion;
    private MaterialButton buttonDeleteRegion;
    private FrameLayout loadingContainer;
    private TextView loadingText;
    private View bottomToolbar;

    private TemplateMatchingService service;
    private TemplateRepository repository;
    private ContentExtractor contentExtractor;

    private int mode;
    private long templateId = -1;
    private long categoryId = -1;
    private String imagePath;

    private Template template;
    private Bitmap templateBitmap;
    private List<TemplateRegion> regions = new ArrayList<>();
    private RegionAdapter regionAdapter;

    private boolean isDrawingMode = false;
    private long nextRegionId = -1;
    
    private int regionCounter = 0;
    private Map<String, Long> detectedToRegionMap = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_template_editor);

        parseIntent();
        initViews();
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            bottomToolbar.setPadding(0, 0, 0, systemBars.bottom);
            return insets;
        });
        
        initService();
        loadData();
    }

    private void parseIntent() {
        Intent intent = getIntent();
        mode = intent.getIntExtra(EXTRA_MODE, MODE_CREATE);
        templateId = intent.getLongExtra(EXTRA_TEMPLATE_ID, -1);
        categoryId = intent.getLongExtra(EXTRA_CATEGORY_ID, -1);
        imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH);
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        regionDrawView = findViewById(R.id.regionDrawView);
        recyclerViewRegions = findViewById(R.id.recyclerViewRegions);
        buttonAddRegion = findViewById(R.id.buttonAddRegion);
        buttonDeleteRegion = findViewById(R.id.buttonDeleteRegion);
        bottomToolbar = findViewById(R.id.bottomToolbar);
        loadingContainer = findViewById(R.id.loadingContainer);
        loadingText = findViewById(R.id.loadingText);

        regionAdapter = new RegionAdapter();
        recyclerViewRegions.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        recyclerViewRegions.setAdapter(regionAdapter);

        regionDrawView.setOnRegionListener(this);
        
        regionDrawView.setOnDetectedRegionClickListener((region, isSelected) -> {
            if (isSelected) {
                addRegionFromDetected(region);
            } else {
                removeRegionByDetectedId(region.getId());
            }
        });

        buttonAddRegion.setOnClickListener(v -> toggleDrawingMode());
        buttonDeleteRegion.setOnClickListener(v -> {
            RegionDrawView.DrawableRegion selected = regionDrawView.getSelectedRegion();
            if (selected != null) {
                regionDrawView.deleteSelectedRegion();
            }
        });

        updateTitle();
    }
    
    private void addRegionFromDetected(DetectedRegion detected) {
        regionCounter++;
        String name = getString(R.string.region_auto_name, regionCounter);
        
        TemplateRegion newRegion = detected.toTemplateRegion(name, templateId);
        newRegion.setSortOrder(regions.size());
        newRegion.setId(nextRegionId--);
        
        regions.add(newRegion);
        detectedToRegionMap.put(detected.getId(), newRegion.getId());
        
        regionDrawView.addRegion(new RegionDrawView.DrawableRegion(
                newRegion.getId(),
                name,
                detected.getBoundingBox()
        ));
        
        regionAdapter.notifyDataSetChanged();
    }
    
    private void removeRegionByDetectedId(String detectedId) {
        Long regionId = detectedToRegionMap.get(detectedId);
        if (regionId == null) return;
        
        detectedToRegionMap.remove(detectedId);
        regions.removeIf(r -> r.getId() == regionId);
        
        for (int i = 0; i < regionDrawView.getRegionCount(); i++) {
            RegionDrawView.DrawableRegion dr = regionDrawView.getRegionAt(i);
            if (dr != null && dr.getId() == regionId) {
                regionDrawView.removeRegion(dr);
                break;
            }
        }
        
        regionAdapter.notifyDataSetChanged();
    }

    private void initService() {
        service = TemplateMatchingService.getInstance(this);
        repository = TemplateRepository.getInstance(this);
        
        AppInitializer initializer = AppInitializer.getInstance(this);
        contentExtractor = new ContentExtractor(this);
        if (initializer.isInitialized()) {
            contentExtractor.setInitializedComponents(
                    initializer.getOcrEngine(),
                    initializer.getBarcodeDecoder()
            );
        }
        
        // 从持久化设置中读取图像增强配置
        CameraSettings settings = CameraConfigManager.getInstance(this).loadSettings();
        if (settings != null) {
            contentExtractor.setEnableEnhance(settings.getEnhanceConfig().isEnableEnhance());
        }
    }

    private void loadData() {
        if (mode == MODE_EDIT && templateId > 0) {
            template = repository.getTemplateWithRegions(templateId);
            if (template != null) {
                imagePath = template.getImagePath();
                regions = new ArrayList<>(template.getRegions());
                categoryId = template.getCategoryId();
                regionCounter = regions.size();
            }
        }

        if (imagePath != null && new File(imagePath).exists()) {
            templateBitmap = BitmapFactory.decodeFile(imagePath);
            regionDrawView.setImageBitmap(templateBitmap);
        } else if (mode == MODE_CREATE) {
            Toast.makeText(this, R.string.error_processing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        List<RegionDrawView.DrawableRegion> drawableRegions = new ArrayList<>();
        for (TemplateRegion region : regions) {
            drawableRegions.add(new RegionDrawView.DrawableRegion(
                    region.getId(),
                    region.getName(),
                    region.getBoundingBox()
            ));
        }
        regionDrawView.setRegions(drawableRegions);
        regionAdapter.notifyDataSetChanged();
        
        if (mode == MODE_CREATE && templateBitmap != null) {
            detectRegions();
        }
    }
    
    private void detectRegions() {
        showLoading(getString(R.string.detecting_regions));
        
        contentExtractor.extract(templateBitmap, new ContentExtractor.ExtractionCallback() {
            @Override
            public void onProgress(int current, int total) {}

            @Override
            public void onComplete(List<DetectedRegion> detectedRegions) {
                hideLoading();
                
                if (detectedRegions == null || detectedRegions.isEmpty()) {
                    Toast.makeText(TemplateEditorActivity.this, 
                            R.string.no_regions_detected, Toast.LENGTH_SHORT).show();
                    return;
                }
                
                regionDrawView.setDetectedRegions(detectedRegions);
//                Log.d(TAG, ">> Detected " + detectedRegions.size() + " regions");
            }

            @Override
            public void onError(Exception e) {
                hideLoading();
//                Log.e(TAG, ">> Region detection failed", e);
                Toast.makeText(TemplateEditorActivity.this, 
                        R.string.region_detection_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    private void showLoading(String message) {
        if (loadingContainer != null) {
            loadingContainer.setVisibility(View.VISIBLE);
            if (loadingText != null) {
                loadingText.setText(message);
            }
        }
    }
    
    private void hideLoading() {
        if (loadingContainer != null) {
            loadingContainer.setVisibility(View.GONE);
        }
    }

    private void updateTitle() {
        toolbar.setTitle(mode == MODE_CREATE ? R.string.template_create : R.string.template_edit);
    }

    private void toggleDrawingMode() {
        isDrawingMode = !isDrawingMode;
        regionDrawView.setDrawingMode(isDrawingMode);
        
        if (isDrawingMode) {
            buttonAddRegion.setText(R.string.cancel);
            Toast.makeText(this, R.string.region_draw_hint, Toast.LENGTH_SHORT).show();
        } else {
            buttonAddRegion.setText(R.string.template_region_add);
        }
    }

    @Override
    public void onRegionCreated(RectF bounds) {
        isDrawingMode = false;
        regionDrawView.setDrawingMode(false);
        buttonAddRegion.setText(R.string.template_region_add);
        showRegionTypeDialog(bounds);
    }
    
    private void showRegionTypeDialog(RectF bounds) {
        String[] types = {
                getString(R.string.template_region_type_barcode),
                getString(R.string.template_region_type_text)
        };
        
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.template_region_type)
                .setItems(types, (dialog, which) -> {
                    TemplateRegion.RegionType type = (which == 0) 
                            ? TemplateRegion.RegionType.BARCODE 
                            : TemplateRegion.RegionType.TEXT;
                    createManualRegion(bounds, type);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
    
    private void createManualRegion(RectF bounds, TemplateRegion.RegionType type) {
        regionCounter++;
        String name = getString(R.string.region_auto_name, regionCounter);
        
        TemplateRegion newRegion = new TemplateRegion(name, templateId, type);
        newRegion.setBoundingBox(bounds);
        newRegion.setSortOrder(regions.size());
        newRegion.setId(nextRegionId--);
        
        regions.add(newRegion);
        
        regionDrawView.addRegion(new RegionDrawView.DrawableRegion(
                newRegion.getId(),
                name,
                bounds
        ));
        
        regionAdapter.notifyDataSetChanged();
    }

    @Override
    public void onRegionSelected(RegionDrawView.DrawableRegion region) {
        buttonDeleteRegion.setEnabled(region != null);
    }

    @Override
    public void onRegionMoved(RegionDrawView.DrawableRegion region, RectF newBounds) {
        for (TemplateRegion r : regions) {
            if (r.getId() == region.getId()) {
                r.setBoundingBox(newBounds);
                break;
            }
        }
    }

    @Override
    public void onRegionDeleted(RegionDrawView.DrawableRegion region) {
        regions.removeIf(r -> r.getId() == region.getId());
        regionAdapter.notifyDataSetChanged();
        buttonDeleteRegion.setEnabled(false);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_template_editor, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_save) {
            saveTemplate();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void saveTemplate() {
        if (mode == MODE_CREATE) {
            showTemplateNameDialog();
        } else {
            saveRegions();
        }
    }

    private void showTemplateNameDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_template_name_simple, null);
        TextInputEditText editName = dialogView.findViewById(R.id.editTextTemplateName);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.template_create)
                .setView(dialogView)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    String name = editName.getText() != null ? editName.getText().toString().trim() : "";
                    
                    if (name.isEmpty()) {
                        Toast.makeText(this, R.string.template_input_name, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    createNewTemplate(name);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void createNewTemplate(String name) {
        if (templateBitmap == null) {
            Toast.makeText(this, R.string.template_save_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        Template newTemplate = service.createTemplate(name, categoryId, templateBitmap);
        if (newTemplate == null) {
            Toast.makeText(this, R.string.template_save_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        templateId = newTemplate.getId();
        template = newTemplate;
        saveRegions();
    }

    private void saveRegions() {
        if (templateId <= 0) {
            Toast.makeText(this, R.string.template_save_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        repository.deleteRegionsByTemplate(templateId);

        for (int i = 0; i < regions.size(); i++) {
            TemplateRegion region = regions.get(i);
            region.setTemplateId(templateId);
            region.setId(0);
            region.setSortOrder(i);
        }
        
        if (service.addRegionsToTemplate(templateId, regions)) {
            Toast.makeText(this, R.string.template_save_success, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } else {
            Toast.makeText(this, R.string.template_save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (templateBitmap != null && !templateBitmap.isRecycled()) {
            templateBitmap.recycle();
        }
        if (contentExtractor != null) {
            contentExtractor.release();
        }
    }

    private class RegionAdapter extends RecyclerView.Adapter<RegionAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_region_chip, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(regions.get(position));
        }

        @Override
        public int getItemCount() {
            return regions.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            MaterialCardView cardView;
            TextView textViewRegionName;
            TextView textViewRegionType;

            ViewHolder(View itemView) {
                super(itemView);
                cardView = (MaterialCardView) itemView;
                textViewRegionName = itemView.findViewById(R.id.textViewRegionName);
                textViewRegionType = itemView.findViewById(R.id.textViewRegionType);
            }

            void bind(TemplateRegion region) {
                textViewRegionName.setText(region.getName());
                
                String typeText = region.getRegionType() == TemplateRegion.RegionType.TEXT
                        ? getString(R.string.template_region_type_text)
                        : getString(R.string.template_region_type_barcode);
                textViewRegionType.setText(typeText);
                textViewRegionType.setVisibility(View.VISIBLE);

                // 区域在列表中就是已选中状态，不需要 checked 状态
                cardView.setChecked(false);

                // 点击移除该区域
                itemView.setOnClickListener(v -> removeRegion(region));
                
                itemView.setOnLongClickListener(v -> {
                    showRegionEditDialog(region);
                    return true;
                });
            }
        }
    }
    
    private void removeRegion(TemplateRegion region) {
        // 从 regions 列表移除
        regions.remove(region);
        
        // 从 RegionDrawView 移除
        for (int i = 0; i < regionDrawView.getRegionCount(); i++) {
            RegionDrawView.DrawableRegion dr = regionDrawView.getRegionAt(i);
            if (dr != null && dr.getId() == region.getId()) {
                regionDrawView.removeRegion(dr);
                break;
            }
        }
        
        // 取消对应检测区域的选中状态
        String detectedIdToRemove = null;
        for (Map.Entry<String, Long> entry : detectedToRegionMap.entrySet()) {
            if (entry.getValue() == region.getId()) {
                detectedIdToRemove = entry.getKey();
                regionDrawView.setDetectedRegionSelected(entry.getKey(), false);
                break;
            }
        }
        if (detectedIdToRemove != null) {
            detectedToRegionMap.remove(detectedIdToRemove);
        }
        
        regionAdapter.notifyDataSetChanged();
    }
    
    private void showRegionEditDialog(TemplateRegion region) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_region_edit_simple, null);
        TextInputEditText editName = dialogView.findViewById(R.id.editTextRegionName);
        
        com.google.android.material.chip.Chip chipBarcode = dialogView.findViewById(R.id.chipBarcode);
        com.google.android.material.chip.Chip chipText = dialogView.findViewById(R.id.chipText);
        
        editName.setText(region.getName());
        if (region.getRegionType() == TemplateRegion.RegionType.TEXT) {
            chipText.setChecked(true);
        } else {
            chipBarcode.setChecked(true);
        }
        
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.template_edit)
                .setView(dialogView)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    String name = editName.getText() != null ? editName.getText().toString().trim() : "";
                    if (name.isEmpty()) name = region.getName();
                    
                    TemplateRegion.RegionType type = chipText.isChecked() 
                            ? TemplateRegion.RegionType.TEXT 
                            : TemplateRegion.RegionType.BARCODE;
                    
                    region.setName(name);
                    region.setRegionType(type);
                    
                    for (int i = 0; i < regionDrawView.getRegionCount(); i++) {
                        RegionDrawView.DrawableRegion dr = regionDrawView.getRegionAt(i);
                        if (dr != null && dr.getId() == region.getId()) {
                            dr.setName(name);
                            break;
                        }
                    }
                    
                    regionDrawView.invalidate();
                    regionAdapter.notifyDataSetChanged();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
