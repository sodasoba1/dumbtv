package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/** Read-only miniature of the home widget grid, updated as staged placements change. */
final class WidgetPreview extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint detail = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private WidgetLayout layout = new WidgetLayout();
    private String customText = "Custom text";

    WidgetPreview(Context context) {
        super(context);
        setFocusable(false);
        setClickable(false);
        fill.setColor(Ui.tileSurface(context, true));
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Ui.dp(context, 1));
        edge.setColor(Ui.isGlass(context) ? 0x88FFFFFF : 0x55888888);
        guide.setStyle(Paint.Style.STROKE);
        guide.setStrokeWidth(Ui.dp(context, 1));
        guide.setColor(0x226F8295);
        title.setColor(Ui.TEXT);
        title.setTypeface(Ui.MEDIUM);
        detail.setColor(Ui.MUTED);
        detail.setTypeface(Ui.LIGHT);
    }

    void setLayout(WidgetLayout value, String text) {
        layout = value == null ? new WidgetLayout() : value;
        customText = text == null || text.trim().isEmpty() ? "Custom text" : text.trim();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = Ui.dp(getContext(), 4);
        float gap = Ui.dp(getContext(), 5);
        float cellW = Math.max(0, (getWidth() - pad * 2 - gap * (WidgetLayout.COLUMNS - 1))
                / (float) WidgetLayout.COLUMNS);
        float cellH = Math.max(0, (getHeight() - pad * 2 - gap * (WidgetLayout.ROWS - 1))
                / (float) WidgetLayout.ROWS);
        float radius = Ui.cornerRadius(getContext());

        for (int y = 0; y < WidgetLayout.ROWS; y++) {
            for (int x = 0; x < WidgetLayout.COLUMNS; x++) {
                float left = pad + x * (cellW + gap);
                float top = pad + y * (cellH + gap);
                canvas.drawRoundRect(left, top, left + cellW, top + cellH, radius, radius, guide);
            }
        }

        for (WidgetLayout.Widget widget : layout.items()) {
            float left = pad + widget.x * (cellW + gap);
            float top = pad + widget.y * (cellH + gap);
            float right = left + widget.width * cellW + (widget.width - 1) * gap;
            float bottom = top + widget.height * cellH + (widget.height - 1) * gap;
            RectF bounds = new RectF(left, top, right, bottom);
            canvas.drawRoundRect(bounds, radius, radius, fill);
            canvas.drawRoundRect(bounds, radius, radius, edge);
            drawContent(canvas, bounds, widget.type);
        }
        if (layout.items().isEmpty()) {
            title.setTextSize(Ui.dp(getContext(), 12));
            drawCentered(canvas, "No widgets placed", getWidth() / 2f, getHeight() / 2f, title);
        }
    }

    private void drawContent(Canvas canvas, RectF bounds, String type) {
        float pad = Ui.dp(getContext(), 7);
        float available = Math.max(0, bounds.width() - pad * 2);
        String heading, body;
        switch (type) {
            case "clock":
                heading = "10:42";
                body = "MONDAY  ·  17 NOV";
                break;
            case "memory":
                heading = "MEMORY";
                body = "62% FREE";
                break;
            case "recent":
                heading = "RECENTLY OPENED";
                body = "Prime Video  ·  YouTube";
                break;
            case "network":
                heading = "NETWORK";
                body = "Wi-Fi connected";
                break;
            case "storage":
                heading = "STORAGE";
                body = "28 GB available";
                break;
            case "text":
                heading = "CUSTOM TEXT";
                body = customText;
                break;
            case "diagnostics":
                heading = "DUMBTV DIAGNOSTICS";
                body = "Keeper running  ·  Permissions OK";
                break;
            case "device":
                heading = "DEVICE INFORMATION";
                body = "Fire TV Stick 4K  ·  Fire OS 6";
                break;
            default:
                heading = type.toUpperCase(java.util.Locale.ROOT);
                body = "";
                break;
        }
        float size = Math.min(Ui.dp(getContext(), 15), bounds.height() * 0.28f);
        title.setTextSize(Math.max(Ui.dp(getContext(), 8), size));
        detail.setTextSize(Math.max(Ui.dp(getContext(), 7), size * 0.58f));
        float baseline = bounds.top + Math.min(bounds.height() * 0.48f, pad + size);
        drawFit(canvas, heading, bounds.centerX(), baseline, title, available);
        if (!body.isEmpty() && bounds.height() > Ui.dp(getContext(), 28)) {
            float bodyBaseline = Math.min(bounds.bottom - pad, baseline + size * 1.25f);
            drawFit(canvas, body, bounds.centerX(), bodyBaseline, detail, available);
        }
    }

    private void drawFit(Canvas canvas, String value, float x, float baseline,
                         TextPaint paint, float width) {
        CharSequence fitted = TextUtils.ellipsize(value, paint, width, TextUtils.TruncateAt.END);
        drawCentered(canvas, fitted, x, baseline, paint);
    }

    private void drawCentered(Canvas canvas, CharSequence value, float x, float baseline, TextPaint paint) {
        float width = paint.measureText(value, 0, value.length());
        canvas.drawText(value, 0, value.length(), x - width / 2f, baseline, paint);
    }
}
