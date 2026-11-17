package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);
        cfg.refresh();
        layout = WidgetLayout.load(cfg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(24), dp(48), dp(24));
        root.setBackgroundColor(0xFF0C0C0E);

        TextView title = Ui.text(this, 28, Ui.TEXT, Ui.LIGHT);
        title.setText("Edit widgets");
        title.setPadding(dp(8), 0, 0, dp(8));
        root.addView(title);

        TextView instructions = Ui.text(this, 13, Ui.MUTED, Ui.NORMAL);
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
        button.setBackground(Ui.round(0xFF252B33, dp(8)));
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
                    cell.setBackground(Ui.round(0xFF171C23, dp(7)));
                } else {
                    boolean selected = occupied.id.equals(selectedId);
                    cell.setText(x == occupied.x && y == occupied.y
                            ? occupied.type.toUpperCase(java.util.Locale.ROOT) + "\n" + occupied.id : "·");
                    cell.setBackground(Ui.round(selected ? cfg.accent() & 0x55FFFFFF | 0x55000000
                            : 0xAA263341, dp(7)));
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
            int width = "clock".equals(type) || "memory".equals(type) ? 2 : 1;
            int height = "clock".equals(type) || "memory".equals(type) ? 2 : 1;
            if (layout.add(type, type, x, y, width, height)) {
                selectedId = type;
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
        List<String> available = new ArrayList<>();
        if (layout.find("clock") == null) available.add("Clock");
        if (layout.find("memory") == null) available.add("Memory");
        if (available.isEmpty()) {
            message("Both built-in widgets are already on the grid");
            return;
        }
        String[] choices = available.toArray(new String[0]);
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Add widget")
                .setItems(choices, (dialog, which) -> {
                    pendingAddType = choices[which].toLowerCase(java.util.Locale.ROOT);
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
        String[] sizes = {"1 × 1", "2 × 1", "2 × 2", "3 × 1", "3 × 2"};
        int[][] dimensions = {{1, 1}, {2, 1}, {2, 2}, {3, 1}, {3, 2}};
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

    private void save() {
        layout.save(cfg);
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
