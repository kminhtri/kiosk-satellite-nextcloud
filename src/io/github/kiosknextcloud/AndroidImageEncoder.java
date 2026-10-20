// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import java.io.*;

/** Decode at bounded resolution, apply EXIF orientation and keep embedded HTML under 512 KiB. */
final class AndroidImageEncoder implements ImageEncoder {
    static final int MAX_JPEG = 350 * 1024;

    @Override public byte[] encode(byte[] original) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(original, 0, original.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Unsupported or damaged photo.");
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1920)
            options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap photo = BitmapFactory.decodeByteArray(original, 0, original.length, options);
        if (photo == null) throw new IOException("Unsupported or damaged photo.");
        try {
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try {
                ExifInterface exif = new ExifInterface(new ByteArrayInputStream(original));
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (IOException ignored) { /* formats without EXIF retain their stored orientation */ }
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(-90); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(-90); break;
                default: break;
            }
            Bitmap rotated = Bitmap.createBitmap(photo, 0, 0, photo.getWidth(), photo.getHeight(), matrix, true);
            if (rotated != photo) { photo.recycle(); photo = rotated; }
            if (photo.hasAlpha()) {
                Bitmap opaque = Bitmap.createBitmap(photo.getWidth(), photo.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(opaque);
                canvas.drawColor(Color.BLACK);
                canvas.drawBitmap(photo, 0, 0, null);
                photo.recycle(); photo = opaque;
            }
            for (int attempt = 0; attempt < 6; attempt++) {
                for (int quality : new int[] {85, 70, 55, 40}) {
                    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                    if (!photo.compress(Bitmap.CompressFormat.JPEG, quality, jpeg)) throw new IOException("Could not encode photo.");
                    if (jpeg.size() <= MAX_JPEG) return jpeg.toByteArray();
                }
                Bitmap smaller = Bitmap.createScaledBitmap(photo, Math.max(1, photo.getWidth() * 3 / 4),
                        Math.max(1, photo.getHeight() * 3 / 4), true);
                if (smaller != photo) { photo.recycle(); photo = smaller; }
            }
            throw new IOException("Photo could not be resized to fit the screensaver.");
        } finally { photo.recycle(); }
    }
}
