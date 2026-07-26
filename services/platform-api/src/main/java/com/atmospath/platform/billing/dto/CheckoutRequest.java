package com.atmospath.platform.billing.dto;

/**
 * @param variantId optional Lemon Squeezy variant; defaults to the
 *                  configured pro variant when null or blank
 */
public record CheckoutRequest(String variantId) {
}
