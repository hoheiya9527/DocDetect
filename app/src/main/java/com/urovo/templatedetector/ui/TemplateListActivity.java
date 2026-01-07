package com.urovo.templatedetector.ui;

import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.urovo.templatedetector.R;
import com.urovo.templatedetector.matcher.TemplateMatchingService;
import com.urovo.templatedetector.matcher.TemplateRepository;
import com.urovo.templatedetector.model.Template;
import com.urovo.templatedetector.model.TemplateCategory;
import com.urovo.templatedetector.model.TemplateRegion;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 模板列表界面
 * 显示分类和模板列表，支持创建、编辑、删除
 */
public class TemplateListActivity extends AppCompatActivity {

    public static final String EXTRA_SELECTED_TEMPLATE_ID = "selected_template_id";
    public static final int REQUEST_CREATE_TEMPLATE = 1001;
    public static final int REQUEST_EDIT_TEMPLATE = 1002;

    private ChipGroup chipGroupCategories;
    private RecyclerView recyclerViewTemplates;
    private View layoutEmpty;
    private FloatingActionButton fabAdd;

    private TemplateMatchingService service;
    private TemplateRepository repository;

    private List<TemplateCategory> categories = new ArrayList<>();
    private List<Template> templates = new ArrayList<>();
    private TemplateAdapter adapter;

    private long selectedCategoryId = -1;
    private boolean isChineseLocale;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_template_list);

        isChineseLocale = Locale.getDefault().getLanguage().equals("zh");

        // 适配系统导航栏
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            // FAB 底部边距适配
            ViewGroup.MarginLayoutParams fabParams = (ViewGroup.MarginLayoutParams) fabAdd.getLayoutParams();
            fabParams.bottomMargin = systemBars.bottom + (int) (16 * getResources().getDisplayMetrics().density);
            fabAdd.setLayoutParams(fabParams);
            return insets;
        });

        initViews();
        initService();
        loadData();
    }

    private void initViews() {
        // Toolbar
        findViewById(R.id.toolbar).setOnClickListener(v -> finish());

        chipGroupCategories = findViewById(R.id.chipGroupCategories);
        recyclerViewTemplates = findViewById(R.id.recyclerViewTemplates);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        fabAdd = findViewById(R.id.fabAdd);

        // RecyclerView
        adapter = new TemplateAdapter();
        recyclerViewTemplates.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewTemplates.setAdapter(adapter);

        // FAB
        fabAdd.setOnClickListener(v -> {
//            showAddMenu()
            startCreateTemplate();
        });

        // 分类选择
        chipGroupCategories.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (!checkedIds.isEmpty()) {
                int checkedId = checkedIds.get(0);
                Chip chip = group.findViewById(checkedId);
                if (chip != null && chip.getTag() instanceof Long) {
                    selectedCategoryId = (Long) chip.getTag();
                    loadTemplates();
                }
            }
        });
    }

    private void initService() {
        service = TemplateMatchingService.getInstance(this);
        repository = TemplateRepository.getInstance(this);
    }

    private void loadData() {
        loadCategories();
        loadTemplates();
    }

    private void loadCategories() {
        categories = service.getAllCategories();

        chipGroupCategories.removeAllViews();

        // 添加"全部"选项
        Chip chipAll = createCategoryChip(-1L,
                isChineseLocale ? "全部" : "All");
        chipGroupCategories.addView(chipAll);

        // 添加分类
        for (TemplateCategory category : categories) {
            Chip chip = createCategoryChip(category.getId(), category.getName());
            chipGroupCategories.addView(chip);
        }

        // 添加"添加分类"按钮
        Chip chipAdd = new Chip(this);
        chipAdd.setText("+");
        chipAdd.setCheckable(false);
        chipAdd.setOnClickListener(v -> showCreateCategoryDialog());
        chipGroupCategories.addView(chipAdd);

        // 默认选中"全部"
        if (selectedCategoryId == -1 && chipGroupCategories.getChildCount() > 0) {
            ((Chip) chipGroupCategories.getChildAt(0)).setChecked(true);
        }
    }

    private Chip createCategoryChip(long categoryId, String name) {
        Chip chip = new Chip(this);
        chip.setText(name);
        chip.setCheckable(true);
        chip.setTag(categoryId);
        chip.setOnLongClickListener(v -> {
            if (categoryId > 0) {
                showCategoryMenu(chip, categoryId);
            }
            return true;
        });
        return chip;
    }

    private void loadTemplates() {
        if (selectedCategoryId == -1) {
            templates = service.getAllTemplates();
        } else {
            templates = service.getTemplatesByCategory(selectedCategoryId);
        }

        // 加载区域数量
        for (Template template : templates) {
            List<TemplateRegion> regions = repository.getRegionsByTemplate(template.getId());
            template.setRegions(regions);
        }

        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        if (templates.isEmpty()) {
            layoutEmpty.setVisibility(View.VISIBLE);
            recyclerViewTemplates.setVisibility(View.GONE);
        } else {
            layoutEmpty.setVisibility(View.GONE);
            recyclerViewTemplates.setVisibility(View.VISIBLE);
        }
    }

//    private void showAddMenu() {
//        PopupMenu popup = new PopupMenu(this, fabAdd);
//        popup.getMenu().add(0, 1, 0, R.string.template_create_from_scan);
//        popup.getMenu().add(0, 2, 0, R.string.category_create);
//
//        popup.setOnMenuItemClickListener(item -> {
//            if (item.getItemId() == 1) {
//                // 从扫描创建模板
//                startCreateTemplate();
//                return true;
//            } else if (item.getItemId() == 2) {
//                showCreateCategoryDialog();
//                return true;
//            }
//            return false;
//        });
//
//        popup.show();
//    }

    private void startCreateTemplate() {
        if (categories.isEmpty()) {
            // 没有分类，先创建分类
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.category_create)
                    .setMessage(R.string.no_categories)
                    .setPositiveButton(R.string.ok, (d, w) -> showCreateCategoryDialog())
                    .show();
            return;
        }

        // 选择分类
        String[] categoryNames = new String[categories.size()];
        for (int i = 0; i < categories.size(); i++) {
            categoryNames[i] = categories.get(i).getName();
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.template_select_category)
                .setItems(categoryNames, (dialog, which) -> {
                    long categoryId = categories.get(which).getId();
                    // 跳转到模板捕获界面
                    Intent intent = new Intent(this, TemplateCaptureActivity.class);
                    intent.putExtra(TemplateCaptureActivity.EXTRA_CATEGORY_ID, categoryId);
                    startActivityForResult(intent, REQUEST_CREATE_TEMPLATE);
                })
                .show();
    }

    private void showCreateCategoryDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_category_edit_simple, null);
        TextInputEditText editName = dialogView.findViewById(R.id.editTextCategoryName);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.category_create)
                .setView(dialogView)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    String name = editName.getText() != null ? editName.getText().toString().trim() : "";

                    if (!name.isEmpty()) {
                        TemplateCategory category = service.createCategory(name);
                        if (category != null) {
                            loadCategories();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showCategoryMenu(View anchor, long categoryId) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, R.string.category_edit);
        popup.getMenu().add(0, 2, 0, R.string.category_delete);

        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                showEditCategoryDialog(categoryId);
                return true;
            } else if (item.getItemId() == 2) {
                showDeleteCategoryConfirm(categoryId);
                return true;
            }
            return false;
        });

        popup.show();
    }

    private void showEditCategoryDialog(long categoryId) {
        TemplateCategory category = repository.getCategoryById(categoryId);
        if (category == null) return;

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_category_edit_simple, null);
        TextInputEditText editName = dialogView.findViewById(R.id.editTextCategoryName);

        editName.setText(category.getName());

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.category_edit)
                .setView(dialogView)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    String name = editName.getText() != null ? editName.getText().toString().trim() : "";

                    if (!name.isEmpty()) {
                        category.setName(name);
                        service.updateCategory(category);
                        loadCategories();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showDeleteCategoryConfirm(long categoryId) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.category_delete)
                .setMessage(R.string.category_delete_confirm)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    service.deleteCategory(categoryId);
                    selectedCategoryId = -1;
                    loadData();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showTemplateMenu(View anchor, Template template) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, R.string.template_test);
        popup.getMenu().add(0, 2, 0, R.string.template_edit);
        popup.getMenu().add(0, 3, 0, R.string.template_delete);

        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                // 测试模板
                Intent intent = new Intent(this, TemplateTestActivity.class);
                intent.putExtra(TemplateTestActivity.EXTRA_TEMPLATE_ID, template.getId());
                startActivity(intent);
                return true;
            } else if (item.getItemId() == 2) {
                // 编辑模板
                Intent intent = new Intent(this, TemplateEditorActivity.class);
                intent.putExtra(TemplateEditorActivity.EXTRA_TEMPLATE_ID, template.getId());
                intent.putExtra(TemplateEditorActivity.EXTRA_MODE, TemplateEditorActivity.MODE_EDIT);
                startActivityForResult(intent, REQUEST_EDIT_TEMPLATE);
                return true;
            } else if (item.getItemId() == 3) {
                showDeleteTemplateConfirm(template);
                return true;
            }
            return false;
        });

        popup.show();
    }

    private void showDeleteTemplateConfirm(Template template) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.template_delete)
                .setMessage(R.string.template_delete_confirm)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    service.deleteTemplate(template.getId());
                    Toast.makeText(this, R.string.template_delete_success, Toast.LENGTH_SHORT).show();
                    loadTemplates();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            loadData();
        }
    }

    /**
     * 模板列表适配器
     */
    private class TemplateAdapter extends RecyclerView.Adapter<TemplateAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_template, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Template template = templates.get(position);
            holder.bind(template);
        }

        @Override
        public int getItemCount() {
            return templates.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView imageViewThumbnail;
            TextView textViewName;
            TextView textViewRegionCount;
            TextView textViewUsageCount;
            ImageButton buttonMore;

            ViewHolder(View itemView) {
                super(itemView);
                imageViewThumbnail = itemView.findViewById(R.id.imageViewThumbnail);
                textViewName = itemView.findViewById(R.id.textViewName);
                textViewRegionCount = itemView.findViewById(R.id.textViewRegionCount);
                textViewUsageCount = itemView.findViewById(R.id.textViewUsageCount);
                buttonMore = itemView.findViewById(R.id.buttonMore);
            }

            void bind(Template template) {
                textViewName.setText(template.getName());

                int regionCount = template.getRegions() != null ? template.getRegions().size() : 0;
                textViewRegionCount.setText(getString(R.string.template_region_count, regionCount));
                textViewUsageCount.setText(getString(R.string.template_usage_count, template.getUsageCount()));

                // 加载缩略图
                String imagePath = template.getImagePath();
                if (imagePath != null && new File(imagePath).exists()) {
                    imageViewThumbnail.setImageBitmap(BitmapFactory.decodeFile(imagePath));
                }

                // 点击编辑
                itemView.setOnClickListener(v -> {
                    Intent intent = new Intent(TemplateListActivity.this, TemplateEditorActivity.class);
                    intent.putExtra(TemplateEditorActivity.EXTRA_TEMPLATE_ID, template.getId());
                    intent.putExtra(TemplateEditorActivity.EXTRA_MODE, TemplateEditorActivity.MODE_EDIT);
                    startActivityForResult(intent, REQUEST_EDIT_TEMPLATE);
                });

                // 更多菜单
                buttonMore.setOnClickListener(v -> showTemplateMenu(v, template));
            }
        }
    }
}
