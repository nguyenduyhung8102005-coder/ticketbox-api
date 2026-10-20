package com.ticketbox.payment.repository;

import com.ticketbox.payment.domain.Payment;
import com.ticketbox.payment.domain.PaymentProvider;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByProviderAndTxnRef(PaymentProvider provider, String txnRef);

    /**
     * Khóa giao dịch khi xử lý IPN / kết quả đối soát. VNPay gọi IPN lặp lại (tối đa 10 lần) và có thể gọi song
     * song với job đối soát: mọi lượt xếp hàng ở dòng này, lượt đầu chốt trạng thái, các lượt sau thấy
     * không còn INITIATED và trả "02 - đã xác nhận".
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.provider = :provider and p.txnRef = :txnRef")
    Optional<Payment> findByTxnRefForUpdate(@Param("provider") PaymentProvider provider,
                                            @Param("txnRef") String txnRef);

    long countByBookingId(Long bookingId);

    /** Giao dịch treo: tạo đã lâu mà chưa nhận IPN (IPN bị mất, server sập lúc VNPay gọi...). */
    @Query("""
            select p.txnRef from Payment p
             where p.provider = :provider
               and p.status = com.ticketbox.payment.domain.PaymentStatus.INITIATED
               and p.createdAt < :before
             order by p.createdAt
            """)
    List<String> findStaleInitiated(@Param("provider") PaymentProvider provider,
                                    @Param("before") Instant before, Pageable pageable);

    /** Đã thu tiền, cần hoàn, nhưng lần hoàn tiền gần nhất đã lâu mà chưa xong. */
    @Query("""
            select p.txnRef from Payment p
             where p.provider = :provider
               and p.status = com.ticketbox.payment.domain.PaymentStatus.REFUND_PENDING
               and p.updatedAt < :before
               and p.refundAttempts < :maxAttempts
             order by p.updatedAt
            """)
    List<String> findStaleRefunds(@Param("provider") PaymentProvider provider,
                                  @Param("before") Instant before,
                                  @Param("maxAttempts") int maxAttempts,
                                  Pageable pageable);
}
