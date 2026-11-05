package com.travel.app.attraction.service;

import com.travel.app.attraction.dto.PlacePhotoBytes;
import com.travel.app.attraction.dto.PlacePhotoResponse;

public interface PlaceService {
    PlacePhotoResponse getPhoto(String name);

    /**
     * 後端 proxy 取得圖片 bytes。回 null 代表查無圖。
     *
     * @param maxWidth 向 Google 索取的最大寬度。**呼叫端必須先驗證過**——每個相異的值都是
     *                 一次獨立的 Place Photos 計費呼叫、也是一筆獨立快取，開放任意值等同
     *                 把額度控制權交給呼叫者。白名單見 {@code PlaceController.ALLOWED_WIDTHS}。
     */
    PlacePhotoBytes getPhotoBytes(String name, int maxWidth);
}
