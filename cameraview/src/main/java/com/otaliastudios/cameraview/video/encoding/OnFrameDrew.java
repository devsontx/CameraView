package com.otaliastudios.cameraview.video.encoding;


import android.graphics.Bitmap;

public interface OnFrameDrew {
    void onDrew(Bitmap frameData, int index);
}
