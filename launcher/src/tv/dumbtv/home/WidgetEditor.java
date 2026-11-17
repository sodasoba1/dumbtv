package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** DPAD-first widget placement editor. Changes are staged until Save is selected. */
public final class WidgetEditor extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private Config cfg;
    private WidgetLayout layout;
    private GridLayout grid;
    private String selectedId;
    private String pendingMove;
    private String pendingAddType;
    private String pendingText;
    private boolean addedMemory;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);
        cfg.refresh();
        layout = WidgetLayout.decode(cfg.get(WidgetLayout.CONFIG_KEY, ""));
        pendingText = cfg.get("widgets.text", "Custom text");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(24), dp(48), dp(24));
        root.setBackground(editorShape(Ui.optionSurface(this), dp(18), Ui.isGlass(this) ? 0x44FFFFFF : 0));

        TextView title = Ui.text(this, 28, Ui.TEXT, Ui.LIGHT);
        title.setText("Edit widgets");
        title.setPadding(dp(8), 0, 0, dp(8));
        root.addView(title);

        TextView instructions = Ui.text(this, 13, Ui.MUTED, Ui.MEDIUM);
        instructions.setText("Use the directional pad to select cells. Select a widget, then Move, Resize or Remove.");
        instructions.setPadding(dp(8), 0, 0, dp(12));
        root.addView(instructions);

        grid = new GridLayout(this);
        grid.setColumnCount(WidgetLayout.COLUMNS);
        grid.setRowCount(WidgetLayout.ROWS);
        grid.setUseDefaultMargins(false);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(MATCH, dp(300));
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
        addAction(actions, "Save", this::save);
        addAction(actions, "Cancel", this::cancel);
        root.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));

        setContentView(root);
        renderGrid(0);
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
                    cell.setBackground(focusableBackground(selected ? (cfg.accent() & 0x00FFFFFF) | 0x55303030
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
                editorShape((cfg.accent() & 0x00FFFFFF) | 0x55303030, radius, cfg.accent()));
        background.addState(new int[0],
                editorShape(normal, radius, Ui.isGlass(this) ? 0x44FFFFFF : 0x00000000));
        return background;
    }

    private GradientDrawable editorShape(int color, int radius, int stroke) {
        GradientDrawable shape = Ui.round(color, radius);
        if (stroke != 0) shape.setStroke(Math.max(1, dp(1)), stroke);
        return shape;
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
            int width = "clock".equals(type) || "memory".equals(type) ? 2
                    : "recent".equals(type) ? 3 : 2;
            int height = 2;
            if (layout.add(type, type, x, y, width, height)) {
                selectedId = type;
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
        String[] types = {"clock", "memory", "recent", "network", "storage", "text"};
        String[] labels = {"Clock", "Memory", "Recently opened apps", "Network", "Storage", "Custom text"};
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
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Add widget")
                .setItems(choiceLabels, (dialog, which) -> {
                    pendingAddType = choices[which];
                    message("Choose an empty cell");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void move() {
        if (selectedId == null) {
            message("Select a widget first");
            return;
        }
        pendingMove = selectedId;
        message("Choose its new top-left cell");
    }

    private void resize() {
        if (selectedId == null) {
            message("Select a widget first");
            return;
        }
        String[] sizes = {"2 × 2", "3 × 2", "4 × 2", "2 × 3", "3 × 3", "4 × 3"};
        int[][] dimensions = {{2, 2}, {3, 2}, {4, 2}, {2, 3}, {3, 3}, {4, 3}};
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Widget size")
                .setItems(sizes, (dialog, which) -> {
                    if (layout.resize(selectedId, dimensions[which][0], dimensions[which][1])) {
                        message("Widget resized");
                    } else {
                        message("That size overlaps another widget or exceeds the grid");
                    }
                    renderGrid(0);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void remove() {
        if (selectedId == null) {
            message("Select a widget first");
            return;
        }
        layout.remove(selectedId);
        selectedId = null;
        pendingMove = null;
        renderGrid(0);
    }

    private void editText() {
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
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Custom widget text")
                .setView(input)
                .setPositiveButton("Use text", (dialog, which) ->
                        pendingText = input.getText().toString().trim())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void save() {
        cfg.set(WidgetLayout.CONFIG_KEY, layout.encode());
        cfg.set("widgets.text", pendingText);
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
