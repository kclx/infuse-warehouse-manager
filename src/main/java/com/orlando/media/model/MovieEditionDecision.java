package com.orlando.media.model;

/**
 * 电影文件 edition 判别结果。
 */
public record MovieEditionDecision(String title, String editionTag, Float confidence, String reason) {
}
