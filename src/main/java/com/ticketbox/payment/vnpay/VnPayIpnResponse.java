package com.ticketbox.payment.vnpay;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phản hồi cho IPN đúng định dạng VNPay yêu cầu: {"RspCode":"00","Message":"Confirm Success"}.
 * VNPay dừng gọi lại khi nhận 00 hoặc 02; các mã khác (01, 04, 97, 99) hoặc timeout thì gọi lại,
 * tối đa 10 lần, mỗi lần cách 5 phút.
 */
public record VnPayIpnResponse(String rspCode, String message) {

    public static final VnPayIpnResponse SUCCESS = new VnPayIpnResponse("00", "Confirm Success");
    public static final VnPayIpnResponse ORDER_NOT_FOUND = new VnPayIpnResponse("01", "Order not found");
    public static final VnPayIpnResponse ALREADY_CONFIRMED = new VnPayIpnResponse("02", "Order already confirmed");
    public static final VnPayIpnResponse INVALID_AMOUNT = new VnPayIpnResponse("04", "Invalid amount");
    public static final VnPayIpnResponse INVALID_SIGNATURE = new VnPayIpnResponse("97", "Invalid signature");
    public static final VnPayIpnResponse UNKNOWN_ERROR = new VnPayIpnResponse("99", "Unknown error");

    public Map<String, String> toBody() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("RspCode", rspCode);
        body.put("Message", message);
        return body;
    }
}
