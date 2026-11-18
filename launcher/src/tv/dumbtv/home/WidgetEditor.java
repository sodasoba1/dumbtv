package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** DPAD-first widget placement editor. Changes are staged until Save is selected. */
public final class WidgetEditor extends Activity {
    static final String EXTRA_WIDGET_ID = "tv.dumbtv.home.extra.WIDGET_ID";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private Config cfg;
    private WidgetLayout layout;
    private GridLayout grid;
    private WidgetPreview preview;
    private TextView selectionInfo;
    private String selectedId;
    private String pendingMove;
    private String pendingAddType;
    private String pendingText;
    private boolean addedMemory;
    private final Map<String, Integer> fontSizes = new HashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);
        cfg.refresh();
        if (!"2".equals(cfg.get("widgets.layout.version", ""))) {
            layout = WidgetLayout.decodeLegacy(cfg.get(WidgetLayout.CONFIG_KEY, ""));
            cfg.set(WidgetLayout.CONFIG_KEY, layout.encode());
            cfg.set("widgets.layout.version", "2");
        } else {
            layout = WidgetLayout.decode(cfg.get(WidgetLayout.CONFIG_KEY, ""));
        }
        pendingText = cfg.getText("widgets.text", "Custom text");
        for (WidgetLayout.Widget widget : layout.items()) {
            int fallback = "clock".equals(widget.type) ? 32
                    : "diagnostics".equals(widget.type) ? 11
                    : "device".equals(widget.type) ? 12
                    : "memory".equals(widget.type) || "storage".equals(widget.type) ? 13 : 14;
            int savedFont = fallback;
            try {
                savedFont = Integer.parseInt(cfg.get("widgets.font." + widget.id,
                        String.valueOf(fallback)));
            } catch (NumberFormatException ignored) {
                // Ignore invalid saved font sizes and use the widget's normal size.
            }
            fontSizes.put(widget.id, savedFont);
        }
        WidgetLayout.Widget initial = layout.find(getIntent().getStringExtra(EXTRA_WIDGET_ID));
        if (initial != null) selectedId = initial.id;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackground(editorShape(Ui.optionSurface(this), dp(18), Ui.isGlass(this) ? 0x44FFFFFF : 0));

        TextView title = Ui.text(this, 28, Ui.TEXT, Ui.LIGHT);
        title.setText("Edit widgets");
        title.setPadding(dp(8), 0, 0, dp(8));
        root.addView(title);

        TextView instructions = Ui.text(this, 13, Ui.MUTED, Ui.MEDIUM);
        instructions.setText("Use the directional pad to select cells. Select a widget, then Move, Resize, Configure or Remove.");
        instructions.setPadding(dp(8), 0, 0, dp(12));
        root.addView(instructions);

        selectionInfo = Ui.text(this, 13, Ui.MUTED, Ui.MEDIUM);
        selectionInfo.setText("No widget selected");
        selectionInfo.setPadding(dp(8), 0, 0, dp(8));
        root.addView(selectionInfo);

        preview = new WidgetPreview(this);
        preview.setLayout(layout, pendingText);
        applyPreviewFonts();
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                MATCH, WidgetGridView.preferredHeight(this));
        previewLp.bottomMargin = dp(10);
        root.addView(preview, previewLp);

        grid = new GridLayout(this);
        grid.setColumnCount(WidgetLayout.COLUMNS);
        grid.setRowCount(WidgetLayout.ROWS);
        grid.setUseDefaultMargins(false);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(MATCH, dp(190));
        gridLp.bottomMargin = dp(16);
        root.addView(grid, gridLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        addAction(actions, "Add", this::add);
        addAction(actions, "Move", this::move);
        addAction(actions, "Resize", this::resize);
        addAction(actions, "Remove", this::remove);
        addAction(actions, "Text", this::editText);
        addAction(actions, "Configure", this::configure);
        addAction(actions, "Save", this::save);
        addAction(actions, "Cancel", this::cancel);
        root.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));

        android.widget.ScrollView page = new android.widget.ScrollView(this);
        page.setFillViewport(true);
        page.setFocusable(false);
        page.setVerticalScrollBarEnabled(false);
        page.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        page.addView(root, new android.widget.ScrollView.LayoutParams(MATCH, WRAP));
        setContentView(page);
        renderGrid(initial == null ? 0 : initial.y * WidgetLayout.COLUMNS + initial.x);
    }

    private void addAction(LinearLayout row, String label, Runnable action) {
        TextView button = Ui.text(this, 14, Ui.TEXT, Ui.MEDIUM);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setClickable(true);
        button.setPadding(dp(8), dp(14), dp(8), dp(14));
        button.setBackground(focusableBackground(Ui.optionSurface(this), dp(8)));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(button, lp);
    }

    private void renderGrid(int focusIndex) {
        grid.removeAllViews();
        updateSelectionInfo();
        preview.setLayout(layout, pendingText);
        applyPreviewFonts();
        for (int row = 0; row < WidgetLayout.ROWS; row++) {
            for (int column = 0; column < WidgetLayout.COLUMNS; column++) {
                final int x = column, y = row, index = row * WidgetLayout.COLUMNS + column;
                WidgetLayout.Widget occupied = occupant(x, y);
                TextView cell = Ui.text(this, 11, Ui.TEXT, Ui.MEDIUM);
                cell.setGravity(Gravity.CENTER);
                cell.setFocusable(true);
                cell.setClickable(true);
                cell.setPadding(dp(4), dp(4), dp(4), dp(4));
                if (occupied == null) {
                    cell.setText("Empty");
                    cell.setBackground(focusableBackground(Ui.tileSurface(this, true), dp(7)));
                } else {
                    boolean selected = occupied.id.equals(selectedId);
                    cell.setText(x == occupied.x && y == occupied.y
                            ? occupied.type.toUpperCase(java.util.Locale.ROOT) + "\n" + occupied.id : "·");
                    cell.setBackground(focusableBackground(selected ? (cfg.accent() & 0x00FFFFFF) | 0x55000000
                            : Ui.optionSurface(this), dp(7)));
                }
                cell.setOnClickListener(v -> chooseCell(x, y));
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                        GridLayout.spec(row, 1, 1f), GridLayout.spec(column, 1, 1f));
                lp.width = 0;
                lp.height = 0;
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                grid.addView(cell, lp);
                if (index == focusIndex) cell.requestFocus();
            }
        }
    }

    private StateListDrawable focusableBackground(int normal, int radius) {
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_focused},
                editorShape((cfg.accent() & 0x00FFFFFF) | 0x55000000, radius,
                        Ui.isGlass(this) ? cfg.accent() : 0));
        background.addState(new int[0],
                editorShape(normal, radius, Ui.isGlass(this) ? 0x44FFFFFF : 0x00000000));
        return background;
    }

    private GradientDrawable editorShape(int color, int radius, int stroke) {
        GradientDrawable shape = Ui.round(color, Ui.cornerRadius(this));
        if (stroke != 0) shape.setStroke(Math.max(1, dp(1)), stroke);
        return shape;
    }

    private void updateSelectionInfo() {
        WidgetLayout.Widget widget = selectedId == null ? null : layout.find(selectedId);
        if (widget == null) {
            selectionInfo.setText(pendingMove != null
                    ? "Choose an empty position for " + pendingMove
                    : pendingAddType != null ? "Choose an empty position for " + pendingAddType
                    : "No widget selected");
            return;
        }
        selectionInfo.setText(widget.type.toUpperCase(java.util.Locale.ROOT)
                + "  ·  " + widget.height + " row(s) × " + widget.width + " column(s)"
                + "  ·  Column " + (widget.x + 1) + ", row " + (widget.y + 1));
    }

    private WidgetLayout.Widget occupant(int x, int y) {
        for (WidgetLayout.Widget widget : layout.items()) {
            if (widget.occupies(x, y)) return widget;
        }
        return null;
    }

    private void chooseCell(int x, int y) {
        if (pendingMove != null) {
            String id = pendingMove;
            pendingMove = null;
            if (layout.move(id, x, y)) {
                selectedId = id;
                message("Widget moved");
            } else {
                message("That position is occupied or outside the grid");
            }
            renderGrid(y * WidgetLayout.COLUMNS + x);
            return;
        }
        if (pendingAddType != null) {
            String type = pendingAddType;
            pendingAddType = null;
            int width = 2;
            int height = 1;
            if (layout.add(type, type, x, y, width, height)) {
                selectedId = type;
                int defaultFont = "clock".equals(type) ? 32
                        : "diagnostics".equals(type) ? 11
                        : "device".equals(type) ? 12
                        : "memory".equals(type) || "storage".equals(type) ? 13 : 14;
                fontSizes.put(type, defaultFont);
                if ("memory".equals(type)) addedMemory = true;
                message("Widget added");
            } else {
                message("That position is occupied or outside the grid");
            }
            renderGrid(y * WidgetLayout.COLUMNS + x);
            return;
        }
        WidgetLayout.Widget widget = occupant(x, y);
        selectedId = widget == null ? null : widget.id;
        renderGrid(y * WidgetLayout.COLUMNS + x);
    }

    private void add() {
        pendingMove = null;
        pendingAddType = null;
        String[] types = {"clock", "memory", "network", "storage", "text", "diagnostics", "device"};
        String[] labels = {"Clock", "Memory", "Network", "Storage", "Custom text", "DumbTV diagnostics", "Device information"};
        List<String> available = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            if (layout.find(types[i]) == null) {
                available.add(types[i]);
                names.add(labels[i]);
            }
        }
        if (available.isEmpty()) {
            message("All available widget types are already on the grid");
            return;
        }
        String[] choices = available.toArray(new String[0]);
        CharSequence[] choiceLabels = names.toArray(new CharSequence[0]);
        AlertDialog dialog = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Add widget")
                .setItems(choiceLabels, (d, which) -> {
                    pendingMove = null;
                    pendingAddType = choices[which];
                    updateSelectionInfo();
                    message("Choose an empty cell");
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
    }

    private void move() {
        pendingAddType = null;
        pendingMove = null;
        if (selectedId == null) {
            message("Select a widget first");
            return;
        }
        pendingMove = selectedId;
        updateSelectionInfo();
        message("Choose its new top-left cell");
    }

    private void resize() {
        pendingMove = null;
        pendingAddType = null;
        if (selectedId == null) {
            message("Select a widget first");
            return;
        }
        List<String> sizeLabels = new ArrayList<>();
        List<int[]> dimensions = new ArrayList<>();
        for (int height = 1; height <= WidgetLayout.ROWS; height++) {
            for (int width = 1; width <= WidgetLayout.COLUMNS; width++) {
                sizeLabels.add(height + " row(s) × " + width + " column(s)");
                dimensions.add(new int[] {width, height});
            }
        }
        String[] sizes = sizeLabels.toArray(new String[0]);
        AlertDialog dialog = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Widget size")
                .setItems(sizes, (d, which) -> {
                    if (layout.resize(selectedId, dimensions.get(which)[0], dimensions.get(which)[1])) {
                        message("Widget resized");
                    } else {
                        message("That size overlaps another widget or exceeds the grid");
                    }
                    renderGrid(0);
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
    }

    private void remove() {
        pendingMove = null;
        pendingAddType = null;
        if (selectedId == null) {
            message("Select a widget first");
            return;
        }
        layout.remove(selectedId);
        fontSizes.remove(selectedId);
        selectedId = null;
        renderGrid(0);
    }

    private void configure() {
        pendingMove = null;
        pendingAddType = null;
        WidgetLayout.Widget widget = selectedId == null ? null : layout.find(selectedId);
        if (widget == null) {
            message("Select a widget first");
            return;
        }
        int current = fontSizes.containsKey(selectedId) ? fontSizes.get(selectedId)
                : ("clock".equals(widget.type) ? 32
                : "diagnostics".equals(widget.type) ? 11
                : "device".equals(widget.type) ? 12
                : "memory".equals(widget.type) || "storage".equals(widget.type) ? 13 : 14);
        int[] sizes = {11, 12, 13, 14, 16, 18, 22, 26, 30, 32, 34, 38};
        String[] labels = new String[sizes.length];
        int selected = 0;
        for (int i = 0; i < sizes.length; i++) {
            labels[i] = sizes[i] + " sp";
            if (sizes[i] == current) selected = i;
        }
        AlertDialog dialog = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Font size · " + widget.type)
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    fontSizes.put(selectedId, sizes[which]);
                    preview.setWidgetFontSize(selectedId, sizes[which]);
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
    }

    private void applyPreviewFonts() {
        for (Map.Entry<String, Integer> entry : fontSizes.entrySet()) {
            preview.setWidgetFontSize(entry.getKey(), entry.getValue());
        }
    }

    private void editText() {
        pendingMove = null;
        pendingAddType = null;
        if (!"text".equals(selectedId)) {
            message("Select the custom text widget first");
            return;
        }
        EditText input = new EditText(this);
        input.setText(pendingText);
        input.setSingleLine(false);
        input.setMinLines(1);
        input.setMaxLines(3);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(80)});
        AlertDialog dialog = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Custom widget text")
                .setView(input)
                .setPositiveButton("Use text", (d, which) -> {
                    pendingText = input.getText().toString().trim();
                    preview.setLayout(layout, pendingText);
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
    }

    private void save() {
        cfg.set(WidgetLayout.CONFIG_KEY, layout.encode());
        cfg.set("widgets.layout.version", "2");
        cfg.setText("widgets.text", pendingText);
        for (WidgetLayout.Widget widget : layout.items()) {
            Integer size = fontSizes.get(widget.id);
            if (size != null) cfg.set("widgets.font." + widget.id, String.valueOf(size));
        }
        if (addedMemory && layout.find("memory") != null) cfg.set(Config.MEMORY.key, "on");
        cfg.set("widgets.initialized", "1");
        setResult(RESULT_OK);
        finish();
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    private void message(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }
}
