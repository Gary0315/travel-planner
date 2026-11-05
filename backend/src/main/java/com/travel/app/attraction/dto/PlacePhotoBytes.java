package com.travel.app.attraction.dto;

// 後端 proxy 取得的原始圖片 bytes + 原始 Content-Type。
// 給 PlaceController 組 ResponseEntity 用，不會序列化成 JSON。
public record PlacePhotoBytes(byte[] bytes, String contentType) {}
