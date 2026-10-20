package com.ticketbox.payment.vnpay;

import com.ticketbox.payment.service.GatewayResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Kết quả API querydr. vnp_ResponseCode cho biết lời gọi TRUY VẤN có thành công không;
 * trạng thái của GIAO DỊCH nằm ở vnp_TransactionStatus.
 */
public record VnPayQueryResult(
        String txnRef,
        String responseCode,
        String message,
        String transactionStatus,
        String transactionNo,
        String bankCode,
        BigDecimal amount,
        Instant payDate,
        Map<String, String> raw) {

    /** VNPay tìm thấy giao dịch. */
    public boolean found() {
        return "00".equals(responseCode);
    }

    /** 91: VNPay không có giao dịch này (khách chưa từng vào trang thanh toán hoặc chưa xác nhận). */
    public boolean notFound() {
        return "91".equals(responseCode);
    }

    public boolean paid() {
        return found() && "00".equals(transactionStatus);
    }

    /** 01: giao dịch chưa hoàn tất (khách đang ở trang thanh toán hoặc đã bỏ dở). */
    public boolean incomplete() {
        return found() && "01".equals(transactionStatus);
    }

    /** 02: lỗi; 04: giao dịch đảo (ngân hàng đã trừ tiền nhưng VNPay không thành công, tiền sẽ được trả lại). */
    public boolean failed() {
        return found() && ("02".equals(transactionStatus) || "04".equals(transactionStatus));
    }

    /** 05: VNPay đang xử lý hoàn tiền; 06: đã gửi yêu cầu hoàn tiền sang ngân hàng. */
    public boolean refundInProgressOrDone() {
        return found() && ("05".equals(transactionStatus) || "06".equals(transactionStatus));
    }

    /** Mã lưu vào payments.response_code: trạng thái giao dịch nếu tìm thấy, ngược lại mã lỗi truy vấn (vd 91). */
    public GatewayResult toGatewayResult() {
        return new GatewayResult(txnRef, amount, paid(), found() ? transactionStatus : responseCode,
                transactionStatus, transactionNo, bankCode, payDate, raw);
    }
}
