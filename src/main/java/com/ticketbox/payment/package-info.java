/**
 * Module Payment: tích hợp cổng thanh toán, xử lý IPN idempotent, đối soát (Giai đoạn 3).
 * <p>
 * Quy tắc ranh giới: module khác chỉ được gọi qua service interface công khai
 * hoặc domain event, không truy cập trực tiếp repository/entity của module này.
 */
package com.ticketbox.payment;
