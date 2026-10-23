package jp.mushamusha.naturalskincamera;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * Conservative, fully on-device skin smoothing.
 *
 * Design goals:
 * - never reshape the face or enlarge eyes
 * - only work strongly inside detected face regions
 * - preserve edges such as eyes, hair, eyebrows and lips
 * - keep memory use bounded on small phones
 */
public final class NaturalSkinProcessor {
    public static final int MAX_PROCESS_DIMENSION = 2048;
    private static final int BLUR_SCALE = 4;

    private NaturalSkinProcessor() {}

    public static Bitmap process(Bitmap source, int strength, List<RectF> sourceFaces) {
        int safeStrength = Math.max(0, Math.min(30, strength));
        if (safeStrength == 0) return source;
        Bitmap working = obtainWorkingBitmap(source, MAX_PROCESS_DIMENSION);
        int w = working.getWidth();
        int h = working.getHeight();

        List<RectF> faces = scaleFaces(sourceFaces, source.getWidth(), source.getHeight(), w, h);

        int smallW = Math.max(1, w / BLUR_SCALE);
        int smallH = Math.max(1, h / BLUR_SCALE);
        Bitmap small = Bitmap.createScaledBitmap(working, smallW, smallH, true);
        int[] smallPixels = new int[smallW * smallH];
        int[] smallTemp = new int[smallW * smallH];
        small.getPixels(smallPixels, 0, smallW, 0, 0, smallW, smallH);

        int radius = safeStrength <= 8 ? 1 : (safeStrength <= 20 ? 2 : 3);
        boxBlurHorizontal(smallPixels, smallTemp, smallW, smallH, radius);
        boxBlurVertical(smallTemp, smallPixels, smallW, smallH, radius);
        small.recycle();

        int[] pixels = new int[w * h];
        working.getPixels(pixels, 0, w, 0, 0, w, h);
        List<FaceRegion> faceRegions = buildFaceRegions(faces, pixels, w, h);

        final float baseAmount = (safeStrength / 30f) * 0.28f;
        for (int y = 0; y < h; y++) {
            int sy = Math.min(smallH - 1, y * smallH / h);
            int row = y * w;
            int srow = sy * smallW;
            for (int x = 0; x < w; x++) {
                int index = row + x;
                int c = pixels[index];
                int r = Color.red(c);
                int g = Color.green(c);
                int b = Color.blue(c);

                if (!isLikelySkin(r, g, b)) continue;

                float faceMask = faceMask(x, y, faceRegions);
                if (faceMask <= 0f) continue;

                int sx = Math.min(smallW - 1, x * smallW / w);
                int bc = smallPixels[srow + sx];
                int br = Color.red(bc);
                int bg = Color.green(bc);
                int bb = Color.blue(bc);

                int detail = Math.abs(r - br) + Math.abs(g - bg) + Math.abs(b - bb);
                float edgeKeep;
                if (detail >= 105) edgeKeep = 0.10f;
                else if (detail >= 70) edgeKeep = 0.32f;
                else if (detail >= 42) edgeKeep = 0.62f;
                else edgeKeep = 1f;

                float amount = baseAmount * faceMask * edgeKeep;
                int nr = blend(r, br, amount);
                int ng = blend(g, bg, amount);
                int nb = blend(b, bb, amount);

                // Redness reduction is intentionally tiny and built into natural skin mode.
                if (nr > ng + 14 && nr > nb + 17 && detail < 55) {
                    float redAmount = (safeStrength / 30f) * 0.055f * faceMask;
                    int targetR = Math.max(ng + 7, (ng + nb) / 2 + 8);
                    nr = blend(nr, targetR, redAmount);
                }

                // Tame only small neutral highlights; do not flatten deliberate highlights.
                int lum = luminance(nr, ng, nb);
                int chroma = Math.max(nr, Math.max(ng, nb)) - Math.min(nr, Math.min(ng, nb));
                if (lum > 218 && chroma < 34 && detail < 45) {
                    float shineAmount = (safeStrength / 30f) * 0.045f * faceMask;
                    nr = blend(nr, br, shineAmount);
                    ng = blend(ng, bg, shineAmount);
                    nb = blend(nb, bb, shineAmount);
                }

                pixels[index] = Color.argb(Color.alpha(c), nr, ng, nb);
            }
        }

        working.setPixels(pixels, 0, w, 0, 0, w, h);
        return working;
    }

    private static Bitmap obtainWorkingBitmap(Bitmap source, int maxDimension) {
        int w = source.getWidth();
        int h = source.getHeight();
        int max = Math.max(w, h);
        if (max <= maxDimension
                && source.getConfig() == Bitmap.Config.ARGB_8888
                && source.isMutable()) {
            return source;
        }
        if (max <= maxDimension) {
            return source.copy(Bitmap.Config.ARGB_8888, true);
        }
        float scale = maxDimension / (float) max;
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, nw, nh, true);
        if (scaled.getConfig() == Bitmap.Config.ARGB_8888 && scaled.isMutable()) return scaled;
        Bitmap copy = scaled.copy(Bitmap.Config.ARGB_8888, true);
        if (scaled != source && !scaled.isRecycled()) scaled.recycle();
        return copy;
    }

    private static List<RectF> scaleFaces(List<RectF> sourceFaces, int sourceW, int sourceH, int targetW, int targetH) {
        List<RectF> out = new ArrayList<>();
        if (sourceFaces == null) return out;
        float sx = targetW / (float) Math.max(1, sourceW);
        float sy = targetH / (float) Math.max(1, sourceH);
        for (RectF f : sourceFaces) {
            if (f == null) continue;
            RectF r = new RectF(f.left * sx, f.top * sy, f.right * sx, f.bottom * sy);
            // Expand a little so cheeks/chin are included, but keep the mask inside the face area.
            float dx = r.width() * 0.10f;
            float dyTop = r.height() * 0.05f;
            float dyBottom = r.height() * 0.14f;
            r.set(r.left - dx, r.top - dyTop, r.right + dx, r.bottom + dyBottom);
            out.add(r);
        }
        return out;
    }

    private static List<FaceRegion> buildFaceRegions(List<RectF> faces, int[] pixels, int imageW, int imageH) {
        List<FaceRegion> out = new ArrayList<>();
        if (faces == null) return out;
        for (RectF r : faces) {
            if (r == null || r.width() <= 1f || r.height() <= 1f) continue;

            // Small/distant faces get gentler smoothing so group photos do not turn tiny
            // faces into flat patches. Very large close-ups are also backed off slightly.
            float relativeWidth = r.width() / Math.max(1f, imageW);
            float sizeAmount;
            if (relativeWidth < 0.15f) sizeAmount = 0.72f;
            else if (relativeWidth < 0.23f) sizeAmount = 0.84f;
            else if (relativeWidth > 0.72f) sizeAmount = 0.90f;
            else sizeAmount = 1.0f;

            // Restore the earlier per-person brightness adjustment, but keep it deliberately
            // tiny (about +/-5%). It samples only likely-skin pixels and never uses identity,
            // ethnicity, sex, age, or any external service.
            int avgLum = sampleFaceLuminance(r, pixels, imageW, imageH);
            float lightAmount = avgLum < 95 ? 1.05f : (avgLum > 185 ? 0.95f : 1.0f);
            out.add(new FaceRegion(r, sizeAmount * lightAmount));
        }
        return out;
    }

    private static int sampleFaceLuminance(RectF face, int[] pixels, int w, int h) {
        int left = Math.max(0, Math.round(face.left + face.width() * 0.15f));
        int right = Math.min(w - 1, Math.round(face.right - face.width() * 0.15f));
        int top = Math.max(0, Math.round(face.top + face.height() * 0.18f));
        int bottom = Math.min(h - 1, Math.round(face.bottom - face.height() * 0.12f));
        if (right < left || bottom < top) return 135;

        int step = Math.max(2, Math.round(Math.min(face.width(), face.height()) / 24f));
        long sum = 0;
        int count = 0;
        for (int y = top; y <= bottom; y += step) {
            int row = y * w;
            for (int x = left; x <= right; x += step) {
                int c = pixels[row + x];
                int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                if (!isLikelySkin(r, g, b)) continue;
                sum += luminance(r, g, b);
                count++;
            }
        }
        return count == 0 ? 135 : (int) (sum / count);
    }

    private static float faceMask(float x, float y, List<FaceRegion> faces) {
        if (faces == null || faces.isEmpty()) {
            // Never guess skin regions when face detection failed. The caller saves the photo unmodified.
            return 0f;
        }
        float best = 0f;
        for (FaceRegion face : faces) {
            RectF r = face.bounds;
            float cx = r.centerX();
            float cy = r.centerY() + r.height() * 0.04f;
            float rx = Math.max(1f, r.width() * 0.52f);
            float ry = Math.max(1f, r.height() * 0.56f);
            float dx = (x - cx) / rx;
            float dy = (y - cy) / ry;
            float d = dx * dx + dy * dy;
            if (d >= 1f) continue;

            float edgeMask = d <= 0.72f ? 1f : (1f - d) / 0.28f;
            float influence = edgeMask * face.amount;
            if (influence > best) best = influence;
        }
        return Math.min(1f, best);
    }

    private static final class FaceRegion {
        final RectF bounds;
        final float amount;

        FaceRegion(RectF bounds, float amount) {
            this.bounds = bounds;
            this.amount = amount;
        }
    }

    private static boolean isLikelySkin(int r, int g, int b) {
        int y = luminance(r, g, b);
        int cb = 128 + ((-43 * r - 85 * g + 128 * b) >> 8);
        int cr = 128 + ((128 * r - 107 * g - 21 * b) >> 8);
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return y > 38 && y < 246 && cb >= 72 && cb <= 145 && cr >= 127 && cr <= 191 && (max - min) >= 9;
    }

    private static int luminance(int r, int g, int b) {
        return (77 * r + 150 * g + 29 * b) >> 8;
    }

    private static int blend(int a, int b, float amount) {
        return clamp(Math.round(a * (1f - amount) + b * amount));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static void boxBlurHorizontal(int[] src, int[] dst, int w, int h, int radius) {
        int span = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sr = 0, sg = 0, sb = 0, sa = 0;
            for (int x = -radius; x <= radius; x++) {
                int ix = Math.max(0, Math.min(w - 1, x));
                int c = src[row + ix];
                sa += Color.alpha(c); sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c);
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = Color.argb(sa / span, sr / span, sg / span, sb / span);
                int removeX = Math.max(0, x - radius);
                int addX = Math.min(w - 1, x + radius + 1);
                int rc = src[row + removeX];
                int ac = src[row + addX];
                sa += Color.alpha(ac) - Color.alpha(rc);
                sr += Color.red(ac) - Color.red(rc);
                sg += Color.green(ac) - Color.green(rc);
                sb += Color.blue(ac) - Color.blue(rc);
            }
        }
    }

    private static void boxBlurVertical(int[] src, int[] dst, int w, int h, int radius) {
        int span = radius * 2 + 1;
        for (int x = 0; x < w; x++) {
            int sr = 0, sg = 0, sb = 0, sa = 0;
            for (int y = -radius; y <= radius; y++) {
                int iy = Math.max(0, Math.min(h - 1, y));
                int c = src[iy * w + x];
                sa += Color.alpha(c); sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c);
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = Color.argb(sa / span, sr / span, sg / span, sb / span);
                int removeY = Math.max(0, y - radius);
                int addY = Math.min(h - 1, y + radius + 1);
                int rc = src[removeY * w + x];
                int ac = src[addY * w + x];
                sa += Color.alpha(ac) - Color.alpha(rc);
                sr += Color.red(ac) - Color.red(rc);
                sg += Color.green(ac) - Color.green(rc);
                sb += Color.blue(ac) - Color.blue(rc);
            }
        }
    }
}
