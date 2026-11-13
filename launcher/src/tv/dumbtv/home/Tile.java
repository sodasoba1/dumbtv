package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputManager;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;

/** A card drawn in one pass: banner, or icon plus name, or an input name; focus ring on top. */
final class Tile extends View {
    private static final float FOCUS_SCALE = 1.08f;

    final Item item;
    final String kind;
    private final CharSequence title;
    private final int cardW, cardH, labelH;
    private final boolean labelAlways, labelOnFocus;
    private final float radius, stroke;
    private final RectF card = new RectF();
    private final Path clip = new Path();
    private final Path arrow = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint inner = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint under = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private boolean moving;
    private final AfterglowTile afterglow;

    Tile(Context c, Item item, String kind, CharSequence title, int cardW, int cardH,
            String labels, int accent) {
        super(c);
        this.item = item;
        this.kind = kind;
        this.title = title;
        this.cardW = cardW;
        this.cardH = cardH;
        boolean app = Catalog.isApp(kind);
        afterglow = app && "afterglow".equals(Config.get(c).get(Config.TILE_STYLE))
                ? new AfterglowTile(c, item.id, title, accent) : null;
        labelH = app && afterglow == null ? Ui.dp(c, 26) : 0;
        labelAlways = "always".equals(labels);
        labelOnFocus = "focus".equals(labels);
        String corners = Config.get(c).get(Config.TILE_RADIUS);
        radius = Ui.dp(c, "square".equals(corners) ? 0 : "rounded".equals(corners) ? 18 : 10);
        stroke = Ui.dp(c, afterglow == null ? 2.5f : 1f);

        fill.setColor(app ? Ui.SURFACE : 0xFF17181B);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(stroke);
        ring.setColor(accent);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(Ui.dp(c, Float.parseFloat(Config.get(c).get(Config.TILE_BORDER_WIDTH))));
        String borderMode = Config.get(c).get(Config.TILE_BORDER);
        outline.setColor("accent".equals(borderMode) ? accent : 0x44FFFFFF);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(Ui.dp(c, 5));
        glow.setColor((accent & 0x00FFFFFF) | 0x55000000);
        focusGlow.setStyle(Paint.Style.STROKE);
        focusGlow.setStrokeWidth(Ui.dp(c, 7));
        String shadow = Config.get(c).get(Config.TILE_SHADOW);
        setElevation(Ui.dp(c, "soft".equals(shadow) ? 4 : 0));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline out) {
                out.setRoundRect(0, 0, cardW, cardH, radius);
            }
        });
        dot.setColor(0xFF34C759);
        inner.setColor(Ui.TEXT);
        inner.setTypeface(Ui.MEDIUM);
        inner.setTextAlign(Paint.Align.CENTER);
        inner.setTextSize(Ui.dp(c, app ? 14 : 17));
        under.setColor(Ui.TEXT);
        under.setTypeface(Ui.MEDIUM);
        under.setTextAlign(Paint.Align.CENTER);
        under.setTextSize(Ui.dp(c, 13));

        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setContentDescription(title);
    }

    void setMoving(boolean on) {
        moving = on;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(cardW, cardH + labelH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        card.set(0, 0, cardW, cardH);
        clip.reset();
        clip.addRoundRect(card, radius, radius, Path.Direction.CW);
        if (afterglow != null) afterglow.resize(cardW, cardH);
        setPivotX(cardW / 2f);
        setPivotY(cardH / 2f);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        animate().cancel();
        Config config = Config.get(getContext());
        if (focused) {
            if ("glow".equals(config.get(Config.TILE_SHADOW))) setElevation(Ui.dp(getContext(), 8));
            float scale;
            switch (config.get(Config.FOCUS_SCALE)) {
                case "off": scale = 1f; break;
                case "subtle": scale = 1.04f; break;
                case "strong": scale = 1.12f; break;
                default: scale = FOCUS_SCALE; break;
            }
            if ("smooth".equals(config.get(Config.FOCUS_ANIMATION))) animate().scaleX(scale).scaleY(scale).setDuration(110).start();
            else { setScaleX(scale); setScaleY(scale); }
        } else {
            setElevation(Ui.dp(getContext(), "soft".equals(config.get(Config.TILE_SHADOW)) ? 4 : 0));
            if ("smooth".equals(config.get(Config.FOCUS_ANIMATION))) animate().scaleX(1f).scaleY(1f).setDuration(110).start();
            else { setScaleX(1f); setScaleY(1f); }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable banner = item.banner, icon = item.icon;
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawRect(card, fill);
        if (afterglow != null) {
            afterglow.draw(canvas, icon, cardW, cardH);
        } else if (banner != null) {
            banner.setBounds(0, 0, cardW, cardH);
            banner.draw(canvas);
        } else if (icon != null) {
            int size = cardH * 46 / 100;
            int left = (cardW - size) / 2, top = cardH * 12 / 100;
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
            drawLine(canvas, inner, cardW / 2f, cardH - cardH * 13 / 100f);
        } else if (!Catalog.isApp(kind)) {
            drawLine(canvas, inner, cardW / 2f, cardH / 2f - (inner.ascent() + inner.descent()) / 2);
        }
        if (moving) canvas.drawColor(0x99000000);
        canvas.restore();

        if (afterglow != null) afterglow.edge(canvas, cardW, cardH, radius);
        String borderMode = Config.get(getContext()).get(Config.TILE_BORDER);
        if (!"off".equals(borderMode) && !isFocused()) {
            float halfBorder = outline.getStrokeWidth() / 2f;
            canvas.drawRoundRect(halfBorder, halfBorder, cardW - halfBorder, cardH - halfBorder,
                    Math.max(0, radius - halfBorder), Math.max(0, radius - halfBorder), outline);
        }
        if (isFocused() && "glow".equals(Config.get(getContext()).get(Config.TILE_SHADOW))) {
            canvas.drawRoundRect(3, 3, cardW - 3, cardH - 3, radius, radius, glow);
        }

        if (item.state == TvInputManager.INPUT_STATE_CONNECTED) {
            float r = Ui.dp(getContext(), 3.5f);
            canvas.drawCircle(cardW - r * 3, r * 3, r, dot);
        }
        if (moving) {
            float half = stroke / 2;
            ring.setPathEffect(new DashPathEffect(new float[] {stroke * 3, stroke * 2}, 0));
            canvas.drawRoundRect(half, half, cardW - half, cardH - half,
                    Math.max(0, radius - half), Math.max(0, radius - half), ring);
            ring.setPathEffect(null);
        } else if (isFocused()) {
            String indicator = Config.get(getContext()).get(Config.FOCUS_INDICATOR);
            boolean white = indicator.startsWith("white");
            int color = white ? Color.WHITE : Config.get(getContext()).accent();
            if (indicator.endsWith("ring")) {
                ring.setColor(color);
                float half = stroke / 2;
                canvas.drawRoundRect(half, half, cardW - half, cardH - half,
                        Math.max(0, radius - half), Math.max(0, radius - half), ring);
            } else {
                focusGlow.setColor((color & 0x00FFFFFF) | 0x55000000);
                float half = focusGlow.getStrokeWidth() / 2;
                canvas.drawRoundRect(half, half, cardW - half, cardH - half,
                        Math.max(0, radius - half), Math.max(0, radius - half), focusGlow);
                ring.setColor(color);
                float ringHalf = stroke / 2;
                canvas.drawRoundRect(ringHalf, ringHalf, cardW - ringHalf, cardH - ringHalf,
                        Math.max(0, radius - ringHalf), Math.max(0, radius - ringHalf), ring);
            }
        }
        if (moving) drawArrows(canvas);
        boolean label = banner != null && (labelAlways || labelOnFocus && isFocused());
        if (labelH > 0 && label) {
            drawLine(canvas, under, cardW / 2f, cardH + labelH - Ui.dp(getContext(), 6));
        }
    }

    private void drawLine(Canvas canvas, TextPaint paint, float x, float baseline) {
        float room = cardW - Ui.dp(getContext(), 16);
        CharSequence s = TextUtils.ellipsize(title, paint, room, TextUtils.TruncateAt.END);
        canvas.drawText(s, 0, s.length(), x, baseline, paint);
    }

    private void drawArrows(Canvas canvas) {
        float h = cardH / 6f, w = h * 0.7f, mid = cardH / 2f, pad = h * 0.8f;
        inner.setStyle(Paint.Style.FILL);
        arrow.reset();
        arrow.moveTo(pad, mid);
        arrow.lineTo(pad + w, mid - h / 2);
        arrow.lineTo(pad + w, mid + h / 2);
        arrow.close();
        arrow.moveTo(cardW - pad, mid);
        arrow.lineTo(cardW - pad - w, mid - h / 2);
        arrow.lineTo(cardW - pad - w, mid + h / 2);
        arrow.close();
        canvas.drawPath(arrow, inner);
    }
}
