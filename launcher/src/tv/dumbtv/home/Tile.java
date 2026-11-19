package tv.dumbtv.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.LinearGradient;
import android.graphics.Shader;
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
    private final float radius, stroke, baseScale;
    private final RectF card = new RectF();
    private final Path clip = new Path();
    private final Path arrow = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        String tileStyle = Config.get(c).get(Config.TILE_STYLE);
        afterglow = app && "afterglow".equals(tileStyle)
                && Config.get(c).get(AppStyle.key(item, "banner"), null) == null
                ? new AfterglowTile(c, item.id, title, accent) : null;
        // All Apps uses one label baseline for banner artwork, icons and Afterglow tiles.
        labelH = app && (afterglow == null || Catalog.ALL_APPS.equals(kind)) ? Ui.dp(c, 26) : 0;
        labelAlways = "always".equals(labels);
        labelOnFocus = "focus".equals(labels);
        radius = Ui.cornerRadius(c);
        baseScale = 1f;
        setScaleX(baseScale);
        setScaleY(baseScale);
        stroke = Ui.dp(c, afterglow == null ? 2.5f : 1f);

        int defaultSurface = Ui.tileSurface(c, app);
        fill.setColor(app ? AppStyle.backgroundColor(Config.get(c), item, defaultSurface) : defaultSurface);
        if (Ui.isGlass(c)) {
            glassSheen.setShader(new LinearGradient(0, 0, 0, cardH,
                    new int[] {0x24FFFFFF, 0x08FFFFFF, 0x00FFFFFF},
                    new float[] {0f, 0.38f, 1f}, Shader.TileMode.CLAMP));
        }
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(stroke);
        ring.setColor(accent);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(Ui.dp(c, Float.parseFloat(Config.get(c).get(Config.TILE_BORDER_WIDTH))));
        String borderMode = Config.get(c).get(Config.TILE_BORDER);
        outline.setColor("accent".equals(borderMode) ? accent
                : Ui.isGlass(c) ? 0x66FFFFFF : 0x44FFFFFF);
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

    int totalHeight() {
        return cardH + labelH;
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
            if ("smooth".equals(config.get(Config.FOCUS_ANIMATION))) animate().scaleX(baseScale * scale).scaleY(baseScale * scale).setDuration(110).start();
            else { setScaleX(baseScale * scale); setScaleY(baseScale * scale); }
        } else {
            setElevation(Ui.dp(getContext(), "soft".equals(AppStyle.get(config, item, "shadow", config.get(Config.TILE_SHADOW))) ? 4 : 0));
            if ("smooth".equals(config.get(Config.FOCUS_ANIMATION))) animate().scaleX(baseScale).scaleY(baseScale).setDuration(110).start();
            else { setScaleX(baseScale); setScaleY(baseScale); }
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
            afterglow.draw(canvas, icon, cardW, cardH, !Catalog.ALL_APPS.equals(kind));
        } else if (banner != null) {
            int sourceWidth = Math.max(1, banner.getIntrinsicWidth());
            int sourceHeight = Math.max(1, banner.getIntrinsicHeight());
            boolean customBackground = Config.get(getContext())
                    .get(AppStyle.key(item, "background"), null) != null;
            int inset = customBackground ? Ui.dp(getContext(), 8) : 0;
            int availableWidth = Math.max(1, cardW - inset * 2);
            int availableHeight = Math.max(1, cardH - inset * 2);
            float scale = Math.min(availableWidth / (float) sourceWidth,
                    availableHeight / (float) sourceHeight);
            int drawWidth = Math.max(1, Math.round(sourceWidth * scale));
            int drawHeight = Math.max(1, Math.round(sourceHeight * scale));
            int left = inset + (availableWidth - drawWidth) / 2;
            int top = inset + (availableHeight - drawHeight) / 2;
            banner.setBounds(left, top, left + drawWidth, top + drawHeight);
            banner.draw(canvas);
        } else if (icon != null) {
            float maxWidth = cardW * 0.88f, maxHeight = cardH * 0.88f;
            float aspect = (float) Math.max(1, icon.getIntrinsicWidth())
                    / Math.max(1, icon.getIntrinsicHeight());
            float iconWidth = Math.min(maxWidth, maxHeight * aspect);
            float iconHeight = Math.min(maxHeight, maxWidth / aspect);
            float left = (cardW - iconWidth) / 2f, top = (cardH - iconHeight) / 2f;
            icon.setBounds(Math.round(left), Math.round(top),
                    Math.round(left + iconWidth), Math.round(top + iconHeight));
            icon.draw(canvas);
        } else if (!Catalog.isApp(kind)) {
            drawLine(canvas, inner, cardW / 2f, cardH / 2f - (inner.ascent() + inner.descent()) / 2);
        }
        if (Ui.isGlass(getContext())) {
            canvas.drawRoundRect(card, radius, radius, glassSheen);
        }
        if (moving) canvas.drawColor(0x99000000);
        canvas.restore();

        if (afterglow != null) afterglow.edge(canvas, cardW, cardH, radius);
        String borderMode = Config.get(getContext()).get(Config.TILE_BORDER);
        outline.setColor("accent".equals(borderMode) ? Config.get(getContext()).accent() : Ui.isGlass(getContext()) ? 0x66FFFFFF : 0x44FFFFFF);
        if ((!"off".equals(borderMode) || Ui.isGlass(getContext())) && !isFocused()) {
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
        String appLabels = AppStyle.get(Config.get(getContext()), item, "labels", Config.get(getContext()).get(Config.LABELS));
        boolean label = banner == null || "always".equals(appLabels)
                || "focus".equals(appLabels) && isFocused();
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
