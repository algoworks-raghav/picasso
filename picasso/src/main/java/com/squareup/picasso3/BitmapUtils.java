/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.picasso3;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.os.Build;
import android.support.annotation.DrawableRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.util.TypedValue;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import okio.BufferedSource;

import static com.squareup.picasso3.Utils.checkNotNull;

final class BitmapUtils {
  /**
   * Lazily create {@link BitmapFactory.Options} based in given
   * {@link Request}, only instantiating them if needed.
   */
  @Nullable static BitmapFactory.Options createBitmapOptions(Request data) {
    final boolean justBounds = data.hasSize();
    BitmapFactory.Options options = null;
    if (justBounds || data.config != null || data.purgeable) {
      options = new BitmapFactory.Options();
      options.inJustDecodeBounds = justBounds;
      options.inInputShareable = data.purgeable;
      options.inPurgeable = data.purgeable;
      if (data.config != null) {
        options.inPreferredConfig = data.config;
      }
    }
    return options;
  }

  static boolean requiresInSampleSize(@Nullable BitmapFactory.Options options) {
    return options != null && options.inJustDecodeBounds;
  }

  static void calculateInSampleSize(int reqWidth, int reqHeight,
      @NonNull BitmapFactory.Options options, Request request) {
    calculateInSampleSize(reqWidth, reqHeight, options.outWidth, options.outHeight, options,
        request);
  }

  static void calculateInSampleSize(int reqWidth, int reqHeight, int width, int height,
      BitmapFactory.Options options, Request request) {
    int sampleSize = 1;
    if (height > reqHeight || width > reqWidth) {
      final int heightRatio;
      final int widthRatio;
      if (reqHeight == 0) {
        sampleSize = (int) Math.floor((float) width / (float) reqWidth);
      } else if (reqWidth == 0) {
        sampleSize = (int) Math.floor((float) height / (float) reqHeight);
      } else {
        heightRatio = (int) Math.floor((float) height / (float) reqHeight);
        widthRatio = (int) Math.floor((float) width / (float) reqWidth);
        sampleSize = request.centerInside
            ? Math.max(heightRatio, widthRatio)
            : Math.min(heightRatio, widthRatio);
      }
    }
    options.inSampleSize = sampleSize;
    options.inJustDecodeBounds = false;
  }

  /**
   * Decode a byte stream into a Bitmap. This method will take into account additional information
   * about the supplied request in order to do the decoding efficiently (such as through leveraging
   * {@code inSampleSize}).
   */
  static Bitmap decodeStream(BufferedSource source, Request request) throws IOException {
    if (Build.VERSION.SDK_INT >= 28) {
      return decodeStreamP(source, request);
    }

    return decodeStreamPreP(source, request);
  }

  @RequiresApi(28)
  private static Bitmap decodeStreamP(BufferedSource source, Request request) throws IOException {
    android.graphics.ImageDecoder.Source imageSource =
        android.graphics.ImageDecoder.createSource(ByteBuffer.wrap(source.readByteArray()));
    return decodeImageSource(imageSource, request);
  }

  @NonNull
  private static Bitmap decodeStreamPreP(BufferedSource bufferedSource, Request request)
      throws IOException {
    boolean isWebPFile = Utils.isWebPFile(bufferedSource);
    boolean isPurgeable = request.purgeable && Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP;
    BitmapFactory.Options options = createBitmapOptions(request);
    boolean calculateSize = requiresInSampleSize(options);

    Bitmap bitmap;
    // We decode from a byte array because, a) when decoding a WebP network stream, BitmapFactory
    // throws a JNI Exception, so we workaround by decoding a byte array, or b) user requested
    // purgeable, which only affects bitmaps decoded from byte arrays.
    if (isWebPFile || isPurgeable) {
      byte[] bytes = bufferedSource.readByteArray();
      if (calculateSize) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        calculateInSampleSize(request.targetWidth, request.targetHeight,
            checkNotNull(options, "options == null"), request);
      }
      bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    } else {
      if (calculateSize) {
        InputStream stream = new SourceBufferingInputStream(bufferedSource);
        BitmapFactory.decodeStream(stream, null, options);
        calculateInSampleSize(request.targetWidth, request.targetHeight,
            checkNotNull(options, "options == null"), request);
      }
      bitmap = BitmapFactory.decodeStream(bufferedSource.inputStream(), null, options);
    }
    if (bitmap == null) {
      // Treat null as an IO exception, we will eventually retry.
      throw new IOException("Failed to decode bitmap.");
    }
    return bitmap;
  }

  static Bitmap decodeResource(Context context, Request request)
      throws IOException {
    if (Build.VERSION.SDK_INT >= 28) {
      return decodeResourceP(context, request);
    }

    Resources resources = Utils.getResources(context, request);
    int id = Utils.getResourceId(resources, request);
    return decodeResourcePreP(resources, id, request);
  }

  @RequiresApi(28)
  private static Bitmap decodeResourceP(Context context, final Request request) throws IOException {
    ImageDecoder.Source imageSource =
        ImageDecoder.createSource(context.getResources(), request.resourceId);
    return decodeImageSource(imageSource, request);
  }

  private static Bitmap decodeResourcePreP(Resources resources, int id, Request request) {
    final BitmapFactory.Options options = createBitmapOptions(request);
    if (requiresInSampleSize(options)) {
      BitmapFactory.decodeResource(resources, id, options);
      calculateInSampleSize(request.targetWidth, request.targetHeight,
          checkNotNull(options, "options == null"), request);
    }
    return BitmapFactory.decodeResource(resources, id, options);
  }

  @RequiresApi(28)
  private static Bitmap decodeImageSource(ImageDecoder.Source imageSource, final Request request)
      throws IOException {
    return ImageDecoder.decodeBitmap(imageSource, new ImageDecoder.OnHeaderDecodedListener() {
      @Override
      public void onHeaderDecoded(@NonNull ImageDecoder imageDecoder,
          @NonNull ImageDecoder.ImageInfo imageInfo, @NonNull ImageDecoder.Source source) {
        if (request.hasSize()) {
          imageDecoder.setTargetSize(request.targetWidth, request.targetHeight);
        }
      }
    });
  }

  static boolean isXmlResource(Resources resources, @DrawableRes int drawableId) {
    TypedValue typedValue = new TypedValue();
    resources.getValue(drawableId, typedValue, true);
    CharSequence file = typedValue.string;
    return file != null && file.toString().endsWith(".xml");
  }

  private BitmapUtils() {
    throw new AssertionError("No instances.");
  }
}