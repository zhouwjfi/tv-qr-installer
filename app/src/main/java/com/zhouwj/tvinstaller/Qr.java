package com.zhouwj.tvinstaller;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.EnumMap;
import java.util.Map;

final class Qr {

    private Qr() {
    }

    static Bitmap generate(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) {
                int off = y * size;
                for (int x = 0; x < size; x++) {
                    px[off + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            b.setPixels(px, 0, size, 0, 0, size, size);
            return b;
        } catch (Exception e) {
            return null;
        }
    }
}
