package com.example.master2;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public class QRCodeUtils {

    /**
     * Generate QR Code bitmap from data string
     * @param data The data to encode in QR code
     * @param width Width of the QR code bitmap
     * @param height Height of the QR code bitmap
     * @return Bitmap of the QR code, or null if generation fails
     */
    public static Bitmap generateQRCodeBitmap(String data, int width, int height) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, width, height);

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);

            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }

            return bitmap;
        } catch (WriterException e) {
            e.printStackTrace();
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Generate a unique key for QR sharing
     * @return Unique string key
     */
    public static String generateUniqueKey() {
        return "qr_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 1000);
    }

    /**
     * Generate QR Code bitmap with default black and white colors
     * @param data The data to encode
     * @param size Size for both width and height (square QR code)
     * @return Bitmap of the QR code
     */
    public static Bitmap generateQRCodeBitmap(String data, int size) {
        return generateQRCodeBitmap(data, size, size);
    }

    /**
     * Generate QR Code bitmap with custom colors
     * @param data The data to encode
     * @param width Width of the QR code
     * @param height Height of the QR code
     * @param foregroundColor Color for QR code pixels (default: black)
     * @param backgroundColor Background color (default: white)
     * @return Bitmap of the QR code
     */
    public static Bitmap generateQRCodeBitmap(String data, int width, int height,
                                              int foregroundColor, int backgroundColor) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, width, height);

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);

            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? foregroundColor : backgroundColor);
                }
            }

            return bitmap;
        } catch (WriterException e) {
            e.printStackTrace();
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Validate if a string is a valid QR data format for this app
     * @param qrData The scanned QR data
     * @return true if valid format, false otherwise
     */
    public static boolean isValidQRFormat(String qrData) {
        if (qrData == null || qrData.trim().isEmpty()) {
            return false;
        }

        // Expected format: shareKey|deviceId|deviceName
        String[] parts = qrData.split("\\|");
        return parts.length >= 3 &&
                !parts[0].trim().isEmpty() &&
                !parts[1].trim().isEmpty() &&
                !parts[2].trim().isEmpty();
    }

    /**
     * Parse QR data into components
     * @param qrData The scanned QR data
     * @return QRData object with parsed components, or null if invalid
     */
    public static QRData parseQRData(String qrData) {
        if (!isValidQRFormat(qrData)) {
            return null;
        }

        String[] parts = qrData.split("\\|");
        return new QRData(parts[0], parts[1], parts[2]);
    }

    /**
     * Helper class to hold parsed QR data
     */
    public static class QRData {
        public final String shareKey;
        public final String deviceId;
        public final String deviceName;

        public QRData(String shareKey, String deviceId, String deviceName) {
            this.shareKey = shareKey;
            this.deviceId = deviceId;
            this.deviceName = deviceName;
        }
    }
}