package tv.dumbtv.home;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import java.util.HashSet;
import java.util.Set;

/**
 * Fixed 6x4 native widget surface. Child bounds are driven exclusively by WidgetLayout.
 */
final class WidgetGridView extends ViewGroup {
    private static final int COLUMNS = WidgetLayout.COLUMNS;
    private static final int ROWS = WidgetLayout.ROWS;
    private static final int GAP_DP = 8;

    private WidgetLayout layout = new WidgetLayout();
    private final Set<String> disabled = new HashSet<>();

    WidgetGridView(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
    }

    void setLayout(WidgetLayout value) {
        layout = value == null ? new WidgetLayout() : value;
        requestLayout();
        invalidate();
    }

    WidgetLayout getLayoutModel() {
        return layout;
    }

    void setWidgetEnabled(String id, boolean enabled) {
        if (enabled) disabled.remove(id);
        else disabled.add(id);
        requestLayout();
    }

    boolean isWidgetEnabled(String id) {
        return !disabled.contains(id);
    }

    void addWidgetView(String widgetId, View child) {
        if (widgetId == null || child == null) throw new IllegalArgumentException("widget id and view required");
        LayoutParams params = new LayoutParams(widgetId);
        addView(child, params);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int contentWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int contentHeight = Math.max(0, height - getPaddingTop() - getPaddingBottom());
        int gap = Ui.dp(getContext(), GAP_DP);
        int cellWidth = Math.max(0, (contentWidth - gap * (COLUMNS - 1)) / COLUMNS);
        int cellHeight = Math.max(0, (contentHeight - gap * (ROWS - 1)) / ROWS);

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            WidgetLayout.Widget widget = layout.find(params.widgetId);
            if (widget == null || disabled.contains(params.widgetId)) {
                child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY));
                continue;
            }
            int childWidth = Math.max(0, cellWidth * widget.width + gap * (widget.width - 1));
            int childHeight = Math.max(0, cellHeight * widget.height + gap * (widget.height - 1));
            child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int contentWidth = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
        int contentHeight = Math.max(0, getHeight() - getPaddingTop() - getPaddingBottom());
        int gap = Ui.dp(getContext(), GAP_DP);
        int cellWidth = Math.max(0, (contentWidth - gap * (COLUMNS - 1)) / COLUMNS);
        int cellHeight = Math.max(0, (contentHeight - gap * (ROWS - 1)) / ROWS);

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            WidgetLayout.Widget widget = layout.find(((LayoutParams) child.getLayoutParams()).widgetId);
            if (widget == null || disabled.contains(((LayoutParams) child.getLayoutParams()).widgetId)) {
                child.layout(0, 0, 0, 0);
                child.setVisibility(GONE);
                continue;
            }
            child.setVisibility(VISIBLE);
            int x = getPaddingLeft() + widget.x * (cellWidth + gap);
            int y = getPaddingTop() + widget.y * (cellHeight + gap);
            child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams("");
    }

    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams params) {
        if (params instanceof LayoutParams) return new LayoutParams((LayoutParams) params);
        return new LayoutParams("");
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams params) {
        return params instanceof LayoutParams;
    }

    static final class LayoutParams extends ViewGroup.LayoutParams {
        final String widgetId;

        LayoutParams(String id) {
            super(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            widgetId = id;
        }

        LayoutParams(LayoutParams source) {
            super(source);
            widgetId = source.widgetId;
        }
    }
}
